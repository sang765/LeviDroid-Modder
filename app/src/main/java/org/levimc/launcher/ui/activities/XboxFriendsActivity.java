package org.levimc.launcher.ui.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.levimc.launcher.R;
import org.levimc.launcher.core.auth.MsftAccountStore;
import org.levimc.launcher.core.xbox.XboxLiveClient;
import org.levimc.launcher.ui.adapter.XboxFriendsAdapter;
import org.levimc.launcher.ui.adapter.XboxRequestsAdapter;
import org.levimc.launcher.ui.adapter.XboxSearchAdapter;
import org.levimc.launcher.ui.animation.DynamicAnim;
import org.levimc.launcher.ui.dialogs.CustomAlertDialog;

import java.util.ArrayList;
import java.util.List;

public class XboxFriendsActivity extends BaseActivity {

    private XboxLiveClient client;
    private XboxFriendsAdapter friendsAdapter;
    private XboxRequestsAdapter requestsAdapter;
    private TextView tabFriends;
    private TextView tabRequests;
    private TextView emptyText;
    private ProgressBar progress;
    private boolean friendsVisible = true;
    private boolean friendsLoaded;
    private boolean requestsLoaded;
    private final List<XboxLiveClient.XboxFriend> knownFriends = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_xbox_friends);

        client = new XboxLiveClient(this);
        tabFriends = findViewById(R.id.tab_friends);
        tabRequests = findViewById(R.id.tab_requests);
        emptyText = findViewById(R.id.empty_text);
        progress = findViewById(R.id.progress);

        RecyclerView friendsList = findViewById(R.id.friends_list);
        RecyclerView requestsList = findViewById(R.id.requests_list);
        friendsList.setLayoutManager(new LinearLayoutManager(this));
        requestsList.setLayoutManager(new LinearLayoutManager(this));

        friendsAdapter = new XboxFriendsAdapter(new XboxFriendsAdapter.Listener() {
            @Override
            public void onViewProfile(XboxLiveClient.XboxFriend friend) {
                Uri uri = Uri.parse("https://www.xbox.com/en-US/play/user?gamertag="
                        + Uri.encode(friend.gamertag));
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            }

            @Override
            public void onRemoveFriend(XboxLiveClient.XboxFriend friend) {
                showRemoveDialog(friend);
            }
        });
        requestsAdapter = new XboxRequestsAdapter(new XboxRequestsAdapter.Listener() {
            @Override
            public void onAccept(XboxLiveClient.XboxFriend friend) {
                performAction(callback -> client.acceptRequest(friend.xuid, callback),
                        getString(R.string.xbox_friends_request_accepted, friend.gamertag));
            }

            @Override
            public void onDecline(XboxLiveClient.XboxFriend friend) {
                performAction(callback -> client.removeFriend(friend.xuid, callback),
                        getString(R.string.xbox_friends_request_declined));
            }
        });
        friendsList.setAdapter(friendsAdapter);
        requestsList.setAdapter(requestsAdapter);

        tabFriends.setOnClickListener(v -> selectTab(true));
        tabRequests.setOnClickListener(v -> selectTab(false));

        View addButton = findViewById(R.id.add_friend_button);
        addButton.setOnClickListener(v -> showSearchDialog());
        DynamicAnim.applyPressScale(addButton);
        View refreshButton = findViewById(R.id.refresh_button);
        refreshButton.setOnClickListener(v -> refreshAll());
        DynamicAnim.applyPressScale(refreshButton);

        selectTab(true);
        refreshAll();
    }

    private void selectTab(boolean friends) {
        friendsVisible = friends;
        tabFriends.setBackgroundResource(friends
                ? R.drawable.bg_tab_selected : R.drawable.bg_tab_unselected);
        tabFriends.setTextColor(getColor(friends ? R.color.on_primary : R.color.text_secondary));
        tabRequests.setBackgroundResource(friends
                ? R.drawable.bg_tab_unselected : R.drawable.bg_tab_selected);
        tabRequests.setTextColor(getColor(friends ? R.color.text_secondary : R.color.on_primary));
        findViewById(R.id.friends_list).setVisibility(friends ? View.VISIBLE : View.GONE);
        findViewById(R.id.requests_list).setVisibility(friends ? View.GONE : View.VISIBLE);
        updateEmpty();
    }

    private void refreshAll() {
        friendsLoaded = false;
        requestsLoaded = false;
        progress.setVisibility(View.VISIBLE);
        updateEmpty();

        client.fetchFriends(new XboxLiveClient.FriendsCallback() {
            @Override
            public void onFriends(List<XboxLiveClient.XboxFriend> friends) {
                knownFriends.clear();
                knownFriends.addAll(friends);
                friendsAdapter.submit(friends);
                friendsLoaded = true;
                finishLoading();
            }

            @Override
            public void onError(String message) {
                friendsLoaded = true;
                finishLoading();
                Toast.makeText(XboxFriendsActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });

        client.fetchIncoming(new XboxLiveClient.ListCallback() {
            @Override
            public void onList(List<XboxLiveClient.XboxFriend> incoming) {
                requestsAdapter.submit(incoming);
                requestsLoaded = true;
                finishLoading();
            }

            @Override
            public void onError(String message) {
                requestsLoaded = true;
                finishLoading();
                Toast.makeText(XboxFriendsActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void finishLoading() {
        if (friendsLoaded && requestsLoaded) {
            progress.setVisibility(View.GONE);
        }
        updateEmpty();
    }

    private void updateEmpty() {
        boolean loaded = friendsVisible ? friendsLoaded : requestsLoaded;
        int count = friendsVisible ? friendsAdapter.getItemCount() : requestsAdapter.getItemCount();
        emptyText.setVisibility(loaded && count == 0 ? View.VISIBLE : View.GONE);
        emptyText.setText(friendsVisible
                ? R.string.xbox_friends_empty_friends
                : R.string.xbox_friends_empty_requests);
    }

    private interface ActionStarter {
        void start(XboxLiveClient.ActionCallback callback);
    }

    private void performAction(ActionStarter starter, String doneMessage) {
        starter.start(new XboxLiveClient.ActionCallback() {
            @Override
            public void onDone() {
                Toast.makeText(XboxFriendsActivity.this, doneMessage, Toast.LENGTH_SHORT).show();
                refreshAll();
            }

            @Override
            public void onError(String message) {
                Toast.makeText(XboxFriendsActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showSearchDialog() {
        View content = getLayoutInflater().inflate(R.layout.dialog_xbox_search, null);
        EditText input = content.findViewById(R.id.xbox_search_edit);
        RecyclerView results = content.findViewById(R.id.search_results);
        results.setLayoutManager(new LinearLayoutManager(this));
        XboxSearchAdapter searchAdapter = new XboxSearchAdapter(friend -> confirmAdd(friend));
        results.setAdapter(searchAdapter);
        new CustomAlertDialog(this)
                .setTitleText(getString(R.string.xbox_friends_add))
                .setCustomView(content)
                .setPositiveButton(null, null)
                .setNegativeButton(getString(R.string.dialog_negative_cancel), null)
                .show();
        Button searchButton = content.findViewById(R.id.xbox_search_button);
        searchButton.setOnClickListener(v -> doSearch(input.getText().toString(), searchAdapter));
        DynamicAnim.applyPressScale(searchButton);
    }

    private void doSearch(String query, XboxSearchAdapter searchAdapter) {
        if (TextUtils.isEmpty(query.trim())) return;
        progress.setVisibility(View.VISIBLE);
        client.searchFriends(query.trim(), new XboxLiveClient.ListCallback() {
            @Override
            public void onList(List<XboxLiveClient.XboxFriend> results) {
                progress.setVisibility(View.GONE);
                List<XboxLiveClient.XboxFriend> filtered = filterSearchResults(results);
                searchAdapter.submit(filtered);
                if (filtered.isEmpty()) {
                    Toast.makeText(XboxFriendsActivity.this,
                            R.string.xbox_friends_search_empty, Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(String message) {
                progress.setVisibility(View.GONE);
                Toast.makeText(XboxFriendsActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** Directory search also returns people already friended and the account itself. */
    private List<XboxLiveClient.XboxFriend> filterSearchResults(List<XboxLiveClient.XboxFriend> results) {
        MsftAccountStore.MsftAccount account = MsftAccountStore.getActive(this);
        String selfXuid = account != null ? account.xuid : null;
        List<XboxLiveClient.XboxFriend> filtered = new ArrayList<>();
        for (XboxLiveClient.XboxFriend candidate : results) {
            if (TextUtils.isEmpty(candidate.xuid)) continue;
            if (candidate.xuid.equals(selfXuid)) continue;
            boolean alreadyFriend = false;
            for (XboxLiveClient.XboxFriend friend : knownFriends) {
                if (candidate.xuid.equals(friend.xuid)) {
                    alreadyFriend = true;
                    break;
                }
            }
            if (!alreadyFriend) filtered.add(candidate);
        }
        return filtered;
    }

    private void confirmAdd(XboxLiveClient.XboxFriend friend) {
        new CustomAlertDialog(this)
                .setTitleText(getString(R.string.xbox_friends_add))
                .setMessage(getString(R.string.xbox_friends_add_confirm, friend.gamertag))
                .setPositiveButton(getString(R.string.xbox_friends_add), v ->
                        performAction(callback -> client.acceptRequest(friend.xuid, callback),
                                getString(R.string.xbox_friends_added, friend.gamertag)))
                .setNegativeButton(getString(R.string.dialog_negative_cancel), null)
                .show();
    }

    private void showRemoveDialog(XboxLiveClient.XboxFriend friend) {
        new CustomAlertDialog(this)
                .setTitleText(getString(R.string.xbox_friends_remove_title))
                .setMessage(getString(R.string.xbox_friends_remove_confirm, friend.gamertag))
                .setPositiveButton(getString(R.string.xbox_friends_remove), v ->
                        performAction(callback -> client.removeFriend(friend.xuid, callback),
                                getString(R.string.xbox_friends_removed, friend.gamertag)))
                .setNegativeButton(getString(R.string.dialog_negative_cancel), null)
                .show();
    }
}
