package org.levimc.launcher.core.xbox;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
 * instead of duplicating the MSA -> user token -> XSTS handshake.
 */
public class XboxLiveClient {

    private static final String PEOPLE_API = "https://people.xboxlive.com";
    private static final String PRESENCE_API = "https://userpresence.xboxlive.com";
    private static final MediaType JSON = MediaType.parse("application/json");
    private static final long TOKEN_MARGIN_MS = 60_000;

    private static final OkHttpClient HTTP = new OkHttpClient();
    private static final Gson GSON = new Gson();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private final Context appContext;
    private final Object tokenLock = new Object();
    private String xblToken;
    private String userHash;
    private long tokenExpireMs;

    public static class XboxFriend {
        public String xuid;
        public String gamertag;
        public String displayPicRaw;
    }

    public static class XboxPresence {
        public String state;
        public String title;
    }

    public interface FriendsCallback {
        void onFriends(List<XboxFriend> friends, Map<String, XboxPresence> presence);

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

    public void fetchFriends(FriendsCallback callback) {
        EXECUTOR.execute(() -> {
            try {
                ensureToken();
                PeopleResponse parsed = GSON.fromJson(
                        get(PEOPLE_API + "/users/me/people"), PeopleResponse.class);
                List<XboxFriend> friends = parsed != null && parsed.people != null
                        ? parsed.people : new ArrayList<>();
                Map<String, XboxPresence> presence = fetchPresence();
                MAIN.post(() -> callback.onFriends(friends, presence));
            } catch (IOException | RuntimeException error) {
                MAIN.post(() -> callback.onError(errorMessage(error)));
            }
        });
    }

    public void searchFriends(String query, ListCallback callback) {
        EXECUTOR.execute(() -> {
            try {
                ensureToken();
                HttpUrl url = HttpUrl.parse(PEOPLE_API + "/users/me/people/search").newBuilder()
                        .addQueryParameter("query", query)
                        .build();
                PeopleResponse parsed = GSON.fromJson(get(url.toString()), PeopleResponse.class);
                List<XboxFriend> results = parsed != null && parsed.people != null
                        ? parsed.people : new ArrayList<>();
                MAIN.post(() -> callback.onList(results));
            } catch (IOException | RuntimeException error) {
                MAIN.post(() -> callback.onError(errorMessage(error)));
            }
        });
    }

    public void fetchIncoming(ListCallback callback) {
        fetchRequestList(PEOPLE_API + "/users/me/people/incoming", callback);
    }

    public void fetchOutgoing(ListCallback callback) {
        fetchRequestList(PEOPLE_API + "/users/me/people/outgoing", callback);
    }

    /** Accepting an incoming request and adding a friend use the same call. */
    public void acceptRequest(String xuid, ActionCallback callback) {
        EXECUTOR.execute(() -> {
            try {
                ensureToken();
                mutate("PUT", PEOPLE_API + "/users/me/people/" + xuid, "{\"relationship\":\"Friend\"}");
                MAIN.post(callback::onDone);
            } catch (IOException error) {
                MAIN.post(() -> callback.onError(error.getMessage()));
            }
        });
    }

    /** Removing a friend, declining a request and cancelling a sent request share this call. */
    public void removeFriend(String xuid, ActionCallback callback) {
        EXECUTOR.execute(() -> {
            try {
                ensureToken();
                mutate("DELETE", PEOPLE_API + "/users/me/people/" + xuid, null);
                MAIN.post(callback::onDone);
            } catch (IOException error) {
                MAIN.post(() -> callback.onError(error.getMessage()));
            }
        });
    }

    private void fetchRequestList(String url, ListCallback callback) {
        EXECUTOR.execute(() -> {
            try {
                ensureToken();
                PeopleResponse parsed = GSON.fromJson(get(url), PeopleResponse.class);
                List<XboxFriend> list = parsed != null && parsed.people != null
                        ? parsed.people : new ArrayList<>();
                MAIN.post(() -> callback.onList(list));
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

    /**
     * Batch presence for the whole friend list; presence errors only degrade the
     * status labels, so an empty map is returned instead of failing the screen.
     */
    private Map<String, XboxPresence> fetchPresence() {
        Map<String, XboxPresence> result = new HashMap<>();
        try {
            JsonArray array = JsonParser.parseString(
                    get(PRESENCE_API + "/users/me/friends?level=real")).getAsJsonArray();
            for (JsonElement element : array) {
                JsonObject entry = element.getAsJsonObject();
                XboxPresence presence = new XboxPresence();
                presence.state = optString(entry, "state");
                if (entry.has("devices")) {
                    for (JsonElement device : entry.getAsJsonArray("devices")) {
                        if (!device.getAsJsonObject().has("titles")) continue;
                        for (JsonElement title : device.getAsJsonObject().getAsJsonArray("titles")) {
                            JsonObject t = title.getAsJsonObject();
                            if ("Active".equals(optString(t, "state"))) {
                                presence.title = optString(t, "name");
                                break;
                            }
                        }
                        if (presence.title != null) break;
                    }
                }
                result.put(optString(entry, "xuid"), presence);
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return result;
    }

    private String get(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "XBL3.0 x=" + userHash + ";" + xblToken)
                .header("x-xbl-contract-version", "3")
                .build();
        try (Response response = HTTP.newCall(request).execute()) {
            return readBody(response);
        }
    }

    private void mutate(String method, String url, String body) throws IOException {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .method(method, body != null ? RequestBody.create(body, JSON) : null)
                .header("Authorization", "XBL3.0 x=" + userHash + ";" + xblToken)
                .header("x-xbl-contract-version", "3");
        try (Response response = HTTP.newCall(builder.build()).execute()) {
            readBody(response);
        }
    }

    private String readBody(Response response) throws IOException {
        if (response.code() == 401 || response.code() == 403) {
            throw new IOException(appContext.getString(R.string.xbox_friends_auth_error));
        }
        if (response.code() == 429) {
            throw new IOException(appContext.getString(R.string.xbox_friends_rate_limited));
        }
        if (!response.isSuccessful() || response.body() == null) {
            throw new IOException(appContext.getString(R.string.xbox_friends_failed));
        }
        return response.body().string();
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

    private static String optString(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static class PeopleResponse {
        List<XboxFriend> people;
    }
}
