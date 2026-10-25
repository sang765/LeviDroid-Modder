package org.levimc.launcher.core.xbox;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.raphimc.minecraftauth.bedrock.BedrockAuthManager;
import net.raphimc.minecraftauth.xbl.model.XblXstsToken;

import org.levimc.launcher.R;
import org.levimc.launcher.core.auth.MsftAccountStore;
import org.levimc.launcher.core.auth.MsftAuthManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Xbox Live client for the launcher's friends feature. Uses the same friends
 * endpoint that the Minecraft Bedrock social manager calls, so the names it
 * returns match what the game shows.
 */
public class XboxLiveClient {

    private static final String TAG = "XboxLiveClient";
    private static final String PEOPLEHUB_API = "https://peoplehub.xboxlive.com";
    private static final String SOCIAL_API = "https://social.xboxlive.com";
    private static final String PROFILE_API = "https://profile.xboxlive.com";
    private static final MediaType JSON = MediaType.parse("application/json");
    private static final long TOKEN_MARGIN_MS = 60_000;

    private static final OkHttpClient HTTP = new OkHttpClient();
    private static final Gson GSON = new Gson();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final RequestBody EMPTY_BODY = RequestBody.create("", JSON);

    private final Context appContext;
    private final Object tokenLock = new Object();
    private String xblToken;
    private String userHash;
    private long tokenExpireMs;
    private String cachedAccountId;
    private String localXuid;

    public static class XboxFriend {
        public String xuid;
        public String gamertag;
        public String modernGamertag;
        public String modernGamertagSuffix;
        public String uniqueModernGamertag;
        public String displayPicRaw;
        public String presenceState;
        public String presenceText;
        public List<PresenceDetail> presenceDetails;
    }

    /** peoplehub rich presence rows use capitalized field names. */
    public static class PresenceDetail {
        public String Device;
        public String PresenceText;
        public String State;
        public String TitleId;
        public Boolean IsPrimary;
    }

    /** Xbox.com profile URLs use the modern gamertag with its suffix, without the '#'. */
    public static String profileSlug(XboxFriend friend) {
        if (friend == null) return "";
        String slug = friend.uniqueModernGamertag;
        if (TextUtils.isEmpty(slug)) {
            slug = TextUtils.isEmpty(friend.modernGamertag)
                    ? friend.gamertag
                    : friend.modernGamertag + (friend.modernGamertagSuffix == null ? "" : friend.modernGamertagSuffix);
        }
        return slug == null ? "" : slug.replace("#", "");
    }

    /** Modern gamertag + suffix (no '#') is what the game and xbox.com display. */
    public static String displayGamertag(String modern, String suffix, String legacy) {
        if (!TextUtils.isEmpty(modern)) {
            return modern + (suffix == null ? "" : suffix.replace("#", ""));
        }
        return legacy == null ? "" : legacy;
    }

    public static String displayName(XboxFriend friend) {
        if (friend == null) return "";
        return displayGamertag(friend.modernGamertag, friend.modernGamertagSuffix, friend.gamertag);
    }

    /** First non-empty rich presence text, e.g. "Minecraft - Playing in Survival Mode". */
    public static String richPresence(XboxFriend friend) {
        if (friend == null) return null;
        if (!TextUtils.isEmpty(friend.presenceText)) return friend.presenceText;
        if (friend.presenceDetails != null) {
            for (PresenceDetail detail : friend.presenceDetails) {
                if (detail != null && !TextUtils.isEmpty(detail.PresenceText)) return detail.PresenceText;
            }
        }
        return null;
    }

    public interface FriendsCallback {
        void onFriends(List<XboxFriend> friends);

        void onError(String message);
    }

    public interface ListCallback {
        void onList(List<XboxFriend> list);

        void onError(String message);
    }

    public interface ActionCallback {
        void onDone();

        void onError(String message);
    }

    public XboxLiveClient(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /** Presence label for a friend, shared by the friends screen and the dashboard card. */
    public static String presenceLabel(Context context, XboxFriend friend) {
        if (friend == null || !"Online".equals(friend.presenceState)) {
            return context.getString(R.string.xbox_friends_offline);
        }
        String text = richPresence(friend);
        if (TextUtils.isEmpty(text) || "Online".equals(text)) {
            return context.getString(R.string.xbox_friends_online);
        }
        return context.getString(R.string.xbox_friends_online_title, text);
    }

    public static int presenceColor(Context context, XboxFriend friend) {
        return presenceColor(context, friend != null ? friend.presenceState : null);
    }

    public static int presenceColor(Context context, String presenceState) {
        return context.getColor("Online".equals(presenceState)
                ? R.color.xbox_online : R.color.text_secondary);
    }

    public void fetchFriends(FriendsCallback callback) {
        EXECUTOR.execute(() -> {
            try {
                ensureToken();
                // MCBE social manager: /users/xuid(<self>)/people/friends contract 7.
                String subpath = TextUtils.isEmpty(localXuid)
                        ? "/users/me/people/friends/decoration/presenceDetail,preferredColor"
                        : "/users/xuid(" + localXuid + ")/people/friends/decoration/presenceDetail,preferredColor";
                PeopleResponse parsed = GSON.fromJson(get(PEOPLEHUB_API + subpath, 7), PeopleResponse.class);
                List<XboxFriend> friends = parsed != null && parsed.people != null
                        ? parsed.people : new ArrayList<>();
                MAIN.post(() -> callback.onFriends(friends));
            } catch (IOException | RuntimeException error) {
                MAIN.post(() -> callback.onError(errorMessage(error)));
            }
        });
    }

    public void searchFriends(String query, ListCallback callback) {
        EXECUTOR.execute(() -> {
            try {
                ensureToken();
                okhttp3.HttpUrl url = okhttp3.HttpUrl.parse(PEOPLEHUB_API
                        + "/users/me/people/search/decoration/detail,preferredColor").newBuilder()
                        .addQueryParameter("q", query)
                        .addQueryParameter("maxItems", "25")
                        .build();
                PeopleResponse parsed = GSON.fromJson(get(url.toString(), 7), PeopleResponse.class);
                List<XboxFriend> results = parsed != null && parsed.people != null
                        ? parsed.people : new ArrayList<>();
                MAIN.post(() -> callback.onList(results));
            } catch (IOException | RuntimeException error) {
                MAIN.post(() -> callback.onError(errorMessage(error)));
            }
        });
    }

    public void fetchIncoming(ListCallback callback) {
        EXECUTOR.execute(() -> {
            try {
                ensureToken();
                PeopleResponse parsed = GSON.fromJson(get(PEOPLEHUB_API
                        + "/users/me/people/friendrequests(received)/decoration/presenceDetail,preferredColor,follower", 7),
                        PeopleResponse.class);
                List<XboxFriend> list = parsed != null && parsed.people != null
                        ? parsed.people : new ArrayList<>();
                MAIN.post(() -> callback.onList(list));
            } catch (IOException | RuntimeException error) {
                MAIN.post(() -> callback.onError(errorMessage(error)));
            }
        });
    }

    public void fetchOutgoing(ListCallback callback) {
        EXECUTOR.execute(() -> {
            try {
                ensureToken();
                PeopleResponse parsed = GSON.fromJson(get(PEOPLEHUB_API
                        + "/users/me/people/friendrequests(sent)/decoration/presenceDetail,preferredColor,follower", 7),
                        PeopleResponse.class);
                List<XboxFriend> list = parsed != null && parsed.people != null
                        ? parsed.people : new ArrayList<>();
                MAIN.post(() -> callback.onList(list));
            } catch (IOException | RuntimeException error) {
                MAIN.post(() -> callback.onError(errorMessage(error)));
            }
        });
    }

    public void acceptRequest(String xuid, ActionCallback callback) {
        mutate("POST", SOCIAL_API + "/bulk/users/me/people/friends/v2?method=add",
                "{\"xuids\":[\"" + xuid + "\"]}", 3, callback);
    }

    public void removeFriend(String xuid, ActionCallback callback) {
        mutate("DELETE", SOCIAL_API + "/users/me/people/xuid(" + xuid + ")", null, 2, callback);
    }

    public interface ProfileCallback {
        void onProfile(XboxProfile profile);

        void onError(String message);
    }

    public static class XboxProfile {
        public String xuid;
        public String gamertag;
        public String gamerscore;
        public String realName;
        public String bio;
        public String location;
        public String displayPicRaw;
        public String presenceState;
        public String presenceText;
        public int friendsCount = -1;
        public int followingCount;
        public int followerCount;
        public boolean callerFollowsTarget;
        public boolean targetFollowsCaller;
    }

    /**
     * Assembles everything the profile screen shows, mirroring what xbox.com
     * renders: profile service settings, social summary counts and rich
     * presence from the peoplehub batch endpoint the social manager uses.
     */
    public void fetchProfile(String xuid, ProfileCallback callback) {
        EXECUTOR.execute(() -> {
            try {
                ensureToken();
                XboxProfile profile = new XboxProfile();
                profile.xuid = xuid;
                loadProfileSettings(xuid, profile);
                loadSocialSummary(xuid, profile);
                loadPresence(xuid, profile);
                loadFriendsCount(xuid, profile);
                MAIN.post(() -> callback.onProfile(profile));
            } catch (IOException | RuntimeException error) {
                MAIN.post(() -> callback.onError(errorMessage(error)));
            }
        });
    }

    /** Profile service batch call; the only step that must succeed. */
    private void loadProfileSettings(String xuid, XboxProfile profile) throws IOException {
        String body = "{\"settings\":[\"Gamertag\",\"ModernGamertag\",\"ModernGamertagSuffix\","
                + "\"UniqueModernGamertag\",\"Gamerscore\",\"GameDisplayPicRaw\",\"Bio\",\"Location\",\"RealName\"],"
                + "\"userIds\":[\"" + xuid + "\"]}";
        ProfileResponse parsed = GSON.fromJson(
                post(PROFILE_API + "/users/batch/profile/settings", body, 3), ProfileResponse.class);
        if (parsed == null || parsed.profileUsers == null || parsed.profileUsers.isEmpty()
                || parsed.profileUsers.get(0).settings == null) {
            return;
        }
        String modern = null;
        String suffix = null;
        String legacy = null;
        String unique = null;
        for (ProfileSetting setting : parsed.profileUsers.get(0).settings) {
            if (setting.value == null) continue;
            switch (setting.id) {
                case "Gamertag":
                    legacy = setting.value;
                    break;
                case "ModernGamertag":
                    modern = setting.value;
                    break;
                case "ModernGamertagSuffix":
                    suffix = setting.value;
                    break;
                case "UniqueModernGamertag":
                    unique = setting.value;
                    break;
                case "Gamerscore":
                    profile.gamerscore = setting.value;
                    break;
                case "Bio":
                    profile.bio = setting.value;
                    break;
                case "Location":
                    profile.location = setting.value;
                    break;
                case "RealName":
                    profile.realName = setting.value;
                    break;
                case "GameDisplayPicRaw":
                    profile.displayPicRaw = setting.value;
                    break;
            }
        }
        String display = displayGamertag(modern, suffix, legacy);
        if (TextUtils.isEmpty(display)) display = unique;
        if (!TextUtils.isEmpty(display)) profile.gamertag = display;
    }

    /** Social service summary: follower/following counts and the caller relationship. */
    private void loadSocialSummary(String xuid, XboxProfile profile) {
        try {
            SocialSummary parsed = GSON.fromJson(
                    get(SOCIAL_API + "/users/xuid(" + xuid + ")/summary", 2), SocialSummary.class);
            if (parsed == null) return;
            profile.followingCount = parsed.targetFollowingCount != null ? parsed.targetFollowingCount : 0;
            profile.followerCount = parsed.targetFollowerCount != null ? parsed.targetFollowerCount : 0;
            profile.callerFollowsTarget = Boolean.TRUE.equals(parsed.isCallerFollowingTarget);
            profile.targetFollowsCaller = Boolean.TRUE.equals(parsed.isTargetFollowingCaller);
        } catch (IOException | RuntimeException error) {
            Log.w(TAG, "social summary failed for " + xuid, error);
        }
    }

    /** peoplehub batch call: rich presence (and a name/pic fallback) for any xuid. */
    private void loadPresence(String xuid, XboxProfile profile) {
        try {
            PeopleResponse parsed = GSON.fromJson(post(PEOPLEHUB_API
                    + "/users/me/people/batch/decoration/presenceDetail,preferredColor",
                    "{\"xuids\":[\"" + xuid + "\"]}", 7), PeopleResponse.class);
            if (parsed == null || parsed.people == null || parsed.people.isEmpty()) return;
            XboxFriend person = parsed.people.get(0);
            profile.presenceState = person.presenceState;
            profile.presenceText = richPresence(person);
            if (TextUtils.isEmpty(profile.gamertag)) profile.gamertag = displayName(person);
            if (TextUtils.isEmpty(profile.displayPicRaw)) profile.displayPicRaw = person.displayPicRaw;
        } catch (IOException | RuntimeException error) {
            Log.w(TAG, "profile presence failed for " + xuid, error);
        }
    }

    /** The two-way friends count is the size of the target's peoplehub friends list. */
    private void loadFriendsCount(String xuid, XboxProfile profile) {
        try {
            PeopleResponse parsed = GSON.fromJson(get(PEOPLEHUB_API
                    + "/users/xuid(" + xuid + ")/people/friends/decoration/presenceDetail", 7),
                    PeopleResponse.class);
            if (parsed != null && parsed.people != null) {
                profile.friendsCount = parsed.people.size();
            }
        } catch (IOException | RuntimeException error) {
            Log.w(TAG, "friends count failed for " + xuid, error);
        }
    }

    /** The friends list of any user; the same peoplehub call the game makes for itself. */
    public void fetchUserFriends(String xuid, ListCallback callback) {
        fetchPeopleList("/users/xuid(" + xuid + ")/people/friends/decoration/presenceDetail,preferredColor", callback);
    }

    /**
     * Best-effort one-way relationship lists; peoplehub documents no
     * caller-facing route for another user's followers/following, so the
     * people screen falls back to the summary counts when this returns nothing.
     */
    public void fetchUserPeople(String xuid, String kind, ListCallback callback) {
        fetchPeopleList("/users/xuid(" + xuid + ")/people/" + kind + "/decoration/presenceDetail,preferredColor", callback);
    }

    private void fetchPeopleList(String subpath, ListCallback callback) {
        EXECUTOR.execute(() -> {
            try {
                ensureToken();
                PeopleResponse parsed = GSON.fromJson(get(PEOPLEHUB_API + subpath, 7), PeopleResponse.class);
                List<XboxFriend> list = parsed != null && parsed.people != null
                        ? parsed.people : new ArrayList<>();
                MAIN.post(() -> callback.onList(list));
            } catch (IOException | RuntimeException error) {
                MAIN.post(() -> callback.onError(errorMessage(error)));
            }
        });
    }

    private void mutate(String method, String url, String body, int contract, ActionCallback callback) {
        EXECUTOR.execute(() -> {
            try {
                ensureToken();
                Request.Builder builder = new Request.Builder()
                        .url(url)
                        .header("Authorization", "XBL3.0 x=" + userHash + ";" + xblToken)
                        .header("x-xbl-contract-version", String.valueOf(contract));
                if (body != null) {
                    builder.post(RequestBody.create(body, JSON));
                } else if ("PUT".equals(method)) {
                    builder.put(EMPTY_BODY);
                } else {
                    builder.method(method, null);
                }
                try (Response response = HTTP.newCall(builder.build()).execute()) {
                    readBody(response);
                }
                MAIN.post(callback::onDone);
            } catch (IOException | RuntimeException error) {
                MAIN.post(() -> callback.onError(errorMessage(error)));
            }
        });
    }

    private String errorMessage(Exception error) {
        return error.getMessage() != null
                ? error.getMessage()
                : appContext.getString(R.string.xbox_friends_failed);
    }

    private String get(String url, int contract) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "XBL3.0 x=" + userHash + ";" + xblToken)
                .header("x-xbl-contract-version", String.valueOf(contract))
                .header("Accept", "application/json")
                .header("Accept-Language", "en-US")
                .build();
        try (Response response = HTTP.newCall(request).execute()) {
            return readBody(response);
        }
    }

    private String post(String url, String body, int contract) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "XBL3.0 x=" + userHash + ";" + xblToken)
                .header("x-xbl-contract-version", String.valueOf(contract))
                .header("Accept", "application/json")
                .header("Accept-Language", "en-US")
                .post(RequestBody.create(body, JSON))
                .build();
        try (Response response = HTTP.newCall(request).execute()) {
            return readBody(response);
        }
    }

    private String readBody(Response response) throws IOException {
        String body = response.body() != null ? response.body().string() : "";
        if (response.isSuccessful()) {
            return body;
        }
        Log.w(TAG, "HTTP " + response.code() + " " + response.request().url() + " " + body);
        if (response.code() == 401 || response.code() == 403) {
            throw new IOException(appContext.getString(R.string.xbox_friends_auth_error));
        }
        if (response.code() == 429) {
            throw new IOException(appContext.getString(R.string.xbox_friends_rate_limited));
        }
        throw new IOException(appContext.getString(R.string.xbox_friends_failed)
                + " (HTTP " + response.code() + xerrDetail(body) + ")");
    }

    /** Xbox reports the concrete failure as an XErr code in the error body. */
    private static String xerrDetail(String body) {
        try {
            JsonObject error = new com.google.gson.JsonParser().parseString(body).getAsJsonObject();
            if (error.has("XErr")) {
                return ", XErr " + error.get("XErr").getAsString();
            }
        } catch (RuntimeException ignored) {
        }
        return "";
    }

    private void ensureToken() throws IOException {
        synchronized (tokenLock) {
            if (xblToken != null && System.currentTimeMillis() < tokenExpireMs - TOKEN_MARGIN_MS) {
                return;
            }
            MsftAccountStore.MsftAccount account = MsftAccountStore.getActive(appContext);
            if (account == null || TextUtils.isEmpty(account.serializedAuthManager)) {
                throw new IOException(appContext.getString(R.string.xbox_friends_sign_in_first));
            }
            if (cachedAccountId != null && !cachedAccountId.equals(account.id)) {
                xblToken = null;
                userHash = null;
                tokenExpireMs = 0;
            }
            cachedAccountId = account.id;
            localXuid = account.xuid;
            try {
                JsonObject json = JsonParser.parseString(account.serializedAuthManager).getAsJsonObject();
                BedrockAuthManager auth = BedrockAuthManager.fromJson(
                        MsftAuthManager.createHttpClient(), MsftAuthManager.GAME_VERSION, json);
                auth.getMsaToken().getChangeListeners().add(() ->
                        MsftAccountStore.updateAuth(appContext, account.id, BedrockAuthManager.toJson(auth).toString()));
                XblXstsToken xsts = auth.getXboxLiveXstsToken().getUpToDate();
                xblToken = xsts.getToken();
                userHash = xsts.getUserHash();
                tokenExpireMs = xsts.getExpireTimeMs();
            } catch (IOException error) {
                throw error;
            } catch (Exception error) {
                throw new IOException(appContext.getString(R.string.xbox_friends_auth_error), error);
            }
        }
    }

    private static class PeopleResponse {
        List<XboxFriend> people;
    }

    private static class ProfileResponse {
        List<ProfileUser> profileUsers;
    }

    private static class ProfileUser {
        String id;
        List<ProfileSetting> settings;
    }

    private static class ProfileSetting {
        String id;
        String value;
    }

    /** Shape of the social service PersonSummary response. */
    private static class SocialSummary {
        Integer targetFollowingCount;
        Integer targetFollowerCount;
        Boolean isCallerFollowingTarget;
        Boolean isTargetFollowingCaller;
    }
}
