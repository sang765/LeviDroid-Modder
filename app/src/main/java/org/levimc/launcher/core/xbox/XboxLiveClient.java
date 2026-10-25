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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Minimal Xbox Live REST client for the friends feature. Reuses the XSTS token
 * already maintained by the MinecraftAuth chain (relying party http://xboxlive.com)
 * and talks to the peoplehub/social endpoints the official Xbox apps use; the
 * legacy people.xboxlive.com v3 surface no longer accepts new clients.
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

    public static class XboxFriend {
        public String xuid;
        public String gamertag;
        public String modernGamertag;
        public String modernGamertagSuffix;
        public String uniqueModernGamertag;
        public String displayPicRaw;
        public String presenceState;
        public String presenceText;
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

    /** The legacy gamertag field lags behind renames; the game and xbox.com show the modern one. */
    public static String displayName(XboxFriend friend) {
        if (friend == null) return "";
        if (!TextUtils.isEmpty(friend.modernGamertag)) {
            String suffix = friend.modernGamertagSuffix == null ? "" : friend.modernGamertagSuffix;
            return friend.modernGamertag + suffix.replace("#", "");
        }
        return friend.gamertag == null ? "" : friend.gamertag;
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
        if (!"Online".equals(friend.presenceState)) {
            return context.getString(R.string.xbox_friends_offline);
        }
        String text = friend.presenceText;
        if (TextUtils.isEmpty(text) || "Online".equals(text)) {
            return context.getString(R.string.xbox_friends_online);
        }
        return context.getString(R.string.xbox_friends_online_title, text);
    }

    public static int presenceColor(Context context, XboxFriend friend) {
        return context.getColor("Online".equals(friend.presenceState)
                ? R.color.xbox_online : R.color.text_secondary);
    }

    public void fetchFriends(FriendsCallback callback) {
        EXECUTOR.execute(() -> {
            try {
                ensureToken();
                PeopleResponse parsed = GSON.fromJson(get(PEOPLEHUB_API
                        + "/users/me/people/friends/decoration/detail,preferredColor,presenceDetail,multiplayerSummary", 7),
                        PeopleResponse.class);
                List<XboxFriend> friends = parsed != null && parsed.people != null
                        ? parsed.people : new ArrayList<>();
                if (refreshProfileFields(friends) <= 0) {
                    refreshSocialNames(friends);
                }
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
                HttpUrl url = HttpUrl.parse(PEOPLEHUB_API
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
                        + "/users/me/people/friendrequests(received)/decoration/detail,preferredColor,follower", 7),
                        PeopleResponse.class);
                List<XboxFriend> list = parsed != null && parsed.people != null
                        ? parsed.people : new ArrayList<>();
                if (refreshProfileFields(list) <= 0) {
                    refreshSocialNames(list);
                }
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
                        + "/users/me/people/friendrequests(sent)/decoration/detail,preferredColor,follower", 7),
                        PeopleResponse.class);
                List<XboxFriend> list = parsed != null && parsed.people != null
                        ? parsed.people : new ArrayList<>();
                if (refreshProfileFields(list) <= 0) {
                    refreshSocialNames(list);
                }
                MAIN.post(() -> callback.onList(list));
            } catch (IOException | RuntimeException error) {
                MAIN.post(() -> callback.onError(errorMessage(error)));
            }
        });
    }

    /** Adding a friend and accepting a request send a real two-way invitation. */
    public void acceptRequest(String xuid, ActionCallback callback) {
        mutate("POST", SOCIAL_API + "/bulk/users/me/people/friends/v2?method=add",
                "{\"xuids\":[\"" + xuid + "\"]}", 3, callback);
    }

    /** Removing a friend and declining a request share this call. */
    public void removeFriend(String xuid, ActionCallback callback) {
        mutate("DELETE", SOCIAL_API + "/users/me/people/xuid(" + xuid + ")", null, 2, callback);
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
                    // Xbox rejects PUT without a request body.
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
                // peoplehub treats Accept-Language as required and 400s without it.
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
                .post(RequestBody.create(body, JSON))
                .build();
        try (Response response = HTTP.newCall(request).execute()) {
            return readBody(response);
        }
    }

    /**
     * PeopleHub keeps decorating people with stale gamertags after a rename, so
     * overlay the fields from the profile service the games themselves use.
     * Best effort. Returns how many display names actually changed, or -1 when
     * the call failed.
     */
    private int refreshProfileFields(List<XboxFriend> people) {
        if (people.isEmpty()) return 0;
        StringBuilder userIds = new StringBuilder();
        for (XboxFriend friend : people) {
            if (TextUtils.isEmpty(friend.xuid)) continue;
            if (userIds.length() > 0) userIds.append(',');
            userIds.append('"').append(friend.xuid).append('"');
        }
        if (userIds.length() == 0) return 0;
        String body = "{\"settings\":[\"Gamertag\",\"ModernGamertag\",\"ModernGamertagSuffix\","
                + "\"UniqueModernGamertag\",\"GameDisplayPicRaw\"],\"userIds\":[" + userIds + "]}";
        try {
            ProfileResponse parsed = GSON.fromJson(
                    post(PROFILE_API + "/users/batch/profile/settings", body, 3), ProfileResponse.class);
            if (parsed == null || parsed.profileUsers == null) {
                Log.i(TAG, "profile overlay: empty response for " + people.size() + " people");
                return 0;
            }
            Map<String, ProfileUser> byXuid = new HashMap<>();
            for (ProfileUser user : parsed.profileUsers) {
                byXuid.put(user.id, user);
            }
            int updated = 0;
            StringBuilder changes = new StringBuilder();
            for (XboxFriend friend : people) {
                ProfileUser user = byXuid.get(friend.xuid);
                if (user == null || user.settings == null) continue;
                String before = displayName(friend);
                for (ProfileSetting setting : user.settings) {
                    if (setting.value == null) continue;
                    switch (setting.id) {
                        case "Gamertag":
                            friend.gamertag = setting.value;
                            break;
                        case "ModernGamertag":
                            friend.modernGamertag = setting.value;
                            break;
                        case "ModernGamertagSuffix":
                            friend.modernGamertagSuffix = setting.value;
                            break;
                        case "UniqueModernGamertag":
                            friend.uniqueModernGamertag = setting.value;
                            break;
                        case "GameDisplayPicRaw":
                            friend.displayPicRaw = setting.value;
                            break;
                    }
                }
                String after = displayName(friend);
                if (!before.equals(after)) {
                    updated++;
                    changes.append(' ').append(before).append("->").append(after);
                }
            }
            Log.i(TAG, "profile overlay: " + updated + "/" + people.size()
                    + " changed from " + parsed.profileUsers.size() + " profiles" + changes);
            return updated;
        } catch (IOException | RuntimeException error) {
            Log.w(TAG, "profile refresh failed, keeping PeopleHub data", error);
            return -1;
        }
    }

    /**
     * Last-resort name source: the legacy social service titles use, which
     * reflects renames faster than PeopleHub. Only consulted when the profile
     * service left the PeopleHub names untouched, so fresh profile data is
     * never overwritten by stale social data.
     */
    private void refreshSocialNames(List<XboxFriend> people) {
        if (people.isEmpty()) return;
        try {
            SocialResponse parsed = GSON.fromJson(
                    get(SOCIAL_API + "/users/me/people?maxItemCount=250", 2), SocialResponse.class);
            if (parsed == null || parsed.people == null) {
                Log.i(TAG, "social overlay: empty response for " + people.size() + " people");
                return;
            }
            Map<String, SocialPerson> byXuid = new HashMap<>();
            for (SocialPerson person : parsed.people) {
                byXuid.put(person.xuid, person);
            }
            int updated = 0;
            StringBuilder changes = new StringBuilder();
            for (XboxFriend friend : people) {
                SocialPerson person = byXuid.get(friend.xuid);
                if (person == null || TextUtils.isEmpty(person.gamertag)) continue;
                String before = displayName(friend);
                friend.gamertag = person.gamertag;
                friend.modernGamertag = person.gamertag;
                friend.modernGamertagSuffix = "";
                friend.uniqueModernGamertag = person.gamertag;
                String after = displayName(friend);
                if (!before.equals(after)) {
                    updated++;
                    changes.append(' ').append(before).append("->").append(after);
                }
            }
            Log.i(TAG, "social overlay: " + updated + "/" + people.size()
                    + " changed from " + parsed.people.size() + " people" + changes);
        } catch (IOException | RuntimeException error) {
            Log.w(TAG, "social overlay failed", error);
        }
    }

    private String readBody(Response response) throws IOException {
        String body = response.body() != null ? response.body().string() : "";
        if (response.isSuccessful()) {
            return body;
        }
        // Surface the status and Xbox's XErr so a broken screen is diagnosable
        // from the user-visible message and logcat instead of a generic toast.
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
            JsonObject error = JsonParser.parseString(body).getAsJsonObject();
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
            // Ensure we never reuse another account's cached session after switching.
            if (cachedAccountId != null && !cachedAccountId.equals(account.id)) {
                synchronized (tokenLock) {
                    xblToken = null;
                    userHash = null;
                    tokenExpireMs = 0;
                    cachedAccountId = account.id;
                }
            }
            cachedAccountId = account.id;
            try {
                JsonObject json = JsonParser.parseString(account.serializedAuthManager).getAsJsonObject();
                BedrockAuthManager auth = BedrockAuthManager.fromJson(
                        MsftAuthManager.createHttpClient(), MsftAuthManager.GAME_VERSION, json);
                // Keep the rotated refresh token persisted, like refreshSavedAccount does.
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

    private static class SocialResponse {
        List<SocialPerson> people;
    }

    private static class SocialPerson {
        String xuid;
        String gamertag;
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
}
