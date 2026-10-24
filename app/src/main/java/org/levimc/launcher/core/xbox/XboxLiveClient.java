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

    public static class XboxFriend {
        public String xuid;
        public String gamertag;
        public String displayPicRaw;
        public String presenceState;
        public String presenceText;
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
                MAIN.post(() -> callback.onList(list));
            } catch (IOException | RuntimeException error) {
                MAIN.post(() -> callback.onError(errorMessage(error)));
            }
        });
    }

    /** Accepting an incoming request and adding a friend use the same call. */
    public void acceptRequest(String xuid, ActionCallback callback) {
        mutate("PUT", SOCIAL_API + "/users/me/people/xuid(" + xuid + ")", callback);
    }

    /** Removing a friend and declining a request share this call. */
    public void removeFriend(String xuid, ActionCallback callback) {
        mutate("DELETE", SOCIAL_API + "/users/me/people/xuid(" + xuid + ")", callback);
    }

    private void mutate(String method, String url, ActionCallback callback) {
        EXECUTOR.execute(() -> {
            try {
                ensureToken();
                Request.Builder builder = new Request.Builder()
                        .url(url)
                        .header("Authorization", "XBL3.0 x=" + userHash + ";" + xblToken)
                        .header("x-xbl-contract-version", "2");
                if ("PUT".equals(method)) {
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
}
