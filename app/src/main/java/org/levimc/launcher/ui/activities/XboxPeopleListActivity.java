package org.levimc.launcher.ui.activities;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.levimc.launcher.R;
import org.levimc.launcher.core.xbox.XboxLiveClient;
import org.levimc.launcher.ui.adapter.XboxPeopleAdapter;
import org.levimc.launcher.ui.animation.DynamicAnim;
import org.levimc.launcher.ui.dialogs.CustomAlertDialog;
import org.levimc.launcher.util.PersonalizationManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Friends / Following / Followers lists of any user, like the xbox.com profile drawer. */
public class XboxPeopleListActivity extends BaseActivity {

    public static final String EXTRA_XUID = "xuid";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_TAB = "tab";

    private static final String[] KINDS = {null, "following", "followers"};

    private XboxLiveClient client;
    private XboxPeopleAdapter adapter;
    private TextView[] tabs;
    private TextView emptyText;
    private ProgressBar progress;
    private EditText filterInput;
    private final Set<String> myFriends = new HashSet<>();
    private String xuid;
    private int currentTab;
    private boolean loading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_xbox_people);
        new PersonalizationManager(this).applyAccentToView(findViewById(android.R.id.content), this);

        client = new XboxLiveClient(this);
        xuid = getIntent().getStringExtra(EXTRA_XUID);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        currentTab = getIntent().getIntExtra(EXTRA_TAB, 0);
        if (xuid == null) {
            finish();
            return;
        }

        TextView titleView = findViewById(R.id.people_title);
        titleView.setText(title == null || title.isEmpty()
                ? getString(R.string.xbox_profile_followers) : title);

        tabs = new TextView[]{findViewById(R.id.tab_friends),
                findViewById(R.id.tab_following), findViewById(R.id.tab_followers)};
        emptyText = findViewById(R.id.empty_text);
        progress = findViewById(R.id.progress);
        filterInput = findViewById(R.id.people_filter);

        RecyclerView list = findViewById(R.id.people_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new XboxPeopleAdapter(myFriends, new XboxPeopleAdapter.Listener() {
            @Override
            public void onOpen(XboxLiveClient.XboxFriend friend) {
                Intent intent = new Intent(XboxPeopleListActivity.this, XboxProfileActivity.class);
                intent.putExtra(XboxProfileActivity.EXTRA_XUID, friend.xuid);
                startActivity(intent);
            }

            @Override
            public void onAdd(XboxLiveClient.XboxFriend friend) {
                confirmAdd(friend);
            }
        });
        list.setAdapter(adapter);

        for (int i = 0; i < tabs.length; i++) {
            final int index = i;
            tabs[i].setOnClickListener(v -> selectTab(index));
            DynamicAnim.applyPressScale(tabs[i]);
        }
        filterInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                adapter.refilter(s.toString());
                updateEmpty();
            }
        });

        loadMyFriends();
        selectTab(Math.max(0, Math.min(currentTab, tabs.length - 1)));
    }

    private void selectTab(int tab) {
        currentTab = tab;
        int accent = new PersonalizationManager(this).getAccentColor();
        for (int i = 0; i < tabs.length; i++) {
            tabs[i].setBackgroundResource(i == tab
                    ? R.drawable.bg_tab_selected : R.drawable.bg_tab_unselected);
            android.graphics.drawable.Drawable background = tabs[i].getBackground();
            if (background instanceof android.graphics.drawable.GradientDrawable) {
                ((android.graphics.drawable.GradientDrawable) background).setColor(i == tab
                        ? accent : ContextCompat.getColor(this, R.color.surface_variant));
            }
            tabs[i].setTextColor(getColor(i == tab
                    ? android.R.color.white : R.color.text_secondary));
        }
        load(tab);
    }

    private void loadMyFriends() {
        client.fetchFriends(new XboxLiveClient.FriendsCallback() {
            @Override
            public void onFriends(List<XboxLiveClient.XboxFriend> friends) {
                for (XboxLiveClient.XboxFriend friend : friends) {
                    if (friend.xuid != null) myFriends.add(friend.xuid);
                }
                adapter.refreshFriendState();
            }

            @Override
            public void onError(String message) {
                // The list still renders; only the add buttons are hidden.
            }
        });
    }

    private void load(int tab) {
        loading = true;
        progress.setVisibility(View.VISIBLE);
        emptyText.setVisibility(View.GONE);
        XboxLiveClient.ListCallback callback = new XboxLiveClient.ListCallback() {
            @Override
            public void onList(List<XboxLiveClient.XboxFriend> people) {
                if (currentTab != tab) return;
                loading = false;
                progress.setVisibility(View.GONE);
                adapter.submit(people == null ? new ArrayList<>() : people);
                updateEmpty();
            }

            @Override
            public void onError(String message) {
                if (currentTab != tab) return;
                loading = false;
                progress.setVisibility(View.GONE);
                adapter.submit(new ArrayList<>());
                emptyText.setText(message);
                emptyText.setVisibility(View.VISIBLE);
            }
        };
        if (KINDS[tab] == null) {
            client.fetchUserFriends(xuid, callback);
        } else {
            client.fetchUserPeople(xuid, KINDS[tab], callback);
        }
    }

    private void updateEmpty() {
        if (loading) return;
        boolean empty = adapter.getItemCount() == 0;
        emptyText.setText(R.string.xbox_profile_empty_list);
        emptyText.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private void confirmAdd(XboxLiveClient.XboxFriend friend) {
        new CustomAlertDialog(this)
                .setTitleText(getString(R.string.xbox_friends_add))
                .setMessage(getString(R.string.xbox_friends_add_confirm, XboxLiveClient.displayName(friend)))
                .setPositiveButton(getString(R.string.xbox_friends_add), v ->
                        client.acceptRequest(friend.xuid, new XboxLiveClient.ActionCallback() {
                            @Override
                            public void onDone() {
                                myFriends.add(friend.xuid);
                                adapter.refreshFriendState();
                                Toast.makeText(XboxPeopleListActivity.this,
                                        getString(R.string.xbox_friends_added, XboxLiveClient.displayName(friend)),
                                        Toast.LENGTH_SHORT).show();
                            }

                            @Override
                            public void onError(String message) {
                                Toast.makeText(XboxPeopleListActivity.this, message, Toast.LENGTH_SHORT).show();
                            }
                        }))
                .setNegativeButton(getString(R.string.dialog_negative_cancel), null)
                .show();
    }
}
