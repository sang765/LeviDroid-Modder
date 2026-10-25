package org.levimc.launcher.ui.activities;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.bumptech.glide.Glide;

import org.levimc.launcher.R;
import org.levimc.launcher.core.auth.MsftAccountStore;
import org.levimc.launcher.core.xbox.XboxLiveClient;
import org.levimc.launcher.ui.animation.DynamicAnim;
import org.levimc.launcher.ui.dialogs.CustomAlertDialog;
import org.levimc.launcher.util.PersonalizationManager;

/** In-launcher profile view for any Xbox user; the launcher-side equivalent of the xbox.com profile. */
public class XboxProfileActivity extends BaseActivity {

    public static final String EXTRA_XUID = "xuid";

    private XboxLiveClient client;
    private String xuid;
    private XboxLiveClient.XboxProfile profile;

    private ImageView avatar;
    private TextView gamertagView;
    private TextView presenceView;
    private TextView detailView;
    private TextView bioView;
    private TextView locationView;
    private TextView friendsCount;
    private TextView followingCount;
    private TextView followersCount;
    private Button actionButton;
    private ProgressBar progress;
    private TextView errorText;
    private View body;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_xbox_profile);
        new PersonalizationManager(this).applyAccentToView(findViewById(android.R.id.content), this);

        client = new XboxLiveClient(this);
        xuid = getIntent().getStringExtra(EXTRA_XUID);
        if (TextUtils.isEmpty(xuid)) {
            finish();
            return;
        }

        avatar = findViewById(R.id.profile_avatar);
        gamertagView = findViewById(R.id.profile_gamertag);
        presenceView = findViewById(R.id.profile_presence);
        detailView = findViewById(R.id.profile_detail);
        bioView = findViewById(R.id.profile_bio);
        locationView = findViewById(R.id.profile_location);
        friendsCount = findViewById(R.id.count_friends);
        followingCount = findViewById(R.id.count_following);
        followersCount = findViewById(R.id.count_followers);
        actionButton = findViewById(R.id.profile_action);
        progress = findViewById(R.id.profile_progress);
        errorText = findViewById(R.id.profile_error);
        body = findViewById(R.id.profile_body);

        findViewById(R.id.counts_friends).setOnClickListener(v -> openPeople(0));
        findViewById(R.id.counts_following).setOnClickListener(v -> openPeople(1));
        findViewById(R.id.counts_followers).setOnClickListener(v -> openPeople(2));
        actionButton.setOnClickListener(v -> confirmFriendAction());
        DynamicAnim.applyPressScale(actionButton);

        load();
    }

    private void load() {
        progress.setVisibility(View.VISIBLE);
        errorText.setVisibility(View.GONE);
        body.setVisibility(View.GONE);

        client.fetchProfile(xuid, new XboxLiveClient.ProfileCallback() {
            @Override
            public void onProfile(XboxLiveClient.XboxProfile loaded) {
                profile = loaded;
                progress.setVisibility(View.GONE);
                body.setVisibility(View.VISIBLE);
                bind(loaded);
            }

            @Override
            public void onError(String message) {
                progress.setVisibility(View.GONE);
                errorText.setVisibility(View.VISIBLE);
                Toast.makeText(XboxProfileActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void bind(XboxLiveClient.XboxProfile profile) {
        Glide.with(avatar)
                .load(profile.displayPicRaw)
                .circleCrop()
                .into(avatar);

        gamertagView.setText(profile.gamertag == null ? "" : profile.gamertag);
        presenceView.setText(profile.presenceState != null && profile.presenceState.contains("Online")
                ? (TextUtils.isEmpty(profile.presenceText)
                ? getString(R.string.xbox_friends_online)
                : getString(R.string.xbox_friends_online_title, profile.presenceText))
                : getString(R.string.xbox_friends_offline));
        presenceView.setTextColor(XboxLiveClient.presenceColor(this, profile.presenceState));

        StringBuilder detail = new StringBuilder();
        if (!TextUtils.isEmpty(profile.gamerscore)) {
            detail.append('G').append(' ').append(profile.gamerscore);
        }
        if (!TextUtils.isEmpty(profile.realName)) {
            if (detail.length() > 0) detail.append("  •  ");
            detail.append(profile.realName);
        }
        if (profile.targetFollowsCaller) {
            if (detail.length() > 0) detail.append("  •  ");
            detail.append(getString(R.string.xbox_profile_follows_you));
        }
        detailView.setText(detail.toString());
        detailView.setVisibility(detail.length() > 0 ? View.VISIBLE : View.GONE);

        bioView.setText(TextUtils.isEmpty(profile.bio)
                ? getString(R.string.xbox_profile_no_bio) : profile.bio);
        if (!TextUtils.isEmpty(profile.location)) {
            locationView.setText(getString(R.string.xbox_profile_location, profile.location));
            locationView.setVisibility(View.VISIBLE);
        } else {
            locationView.setVisibility(View.GONE);
        }

        friendsCount.setText(profile.friendsCount >= 0 ? String.valueOf(profile.friendsCount) : "—");
        followingCount.setText(String.valueOf(profile.followingCount));
        followersCount.setText(String.valueOf(profile.followerCount));

        setupActionButton(profile);
    }

    private void setupActionButton(XboxLiveClient.XboxProfile profile) {
        MsftAccountStore.MsftAccount account = MsftAccountStore.getActive(this);
        if (account != null && account.xuid != null && account.xuid.equals(profile.xuid)) {
            actionButton.setVisibility(View.GONE);
            return;
        }
        actionButton.setVisibility(View.VISIBLE);
        if (profile.callerFollowsTarget) {
            actionButton.setText(R.string.xbox_profile_unfollow);
        } else {
            actionButton.setText(R.string.xbox_friends_add);
        }
    }

    private void confirmFriendAction() {
        if (profile == null) return;
        boolean remove = profile.callerFollowsTarget;
        int label = remove ? R.string.xbox_profile_unfollow : R.string.xbox_friends_add;
        new CustomAlertDialog(this)
                .setTitleText(getString(label))
                .setMessage(remove
                        ? getString(R.string.xbox_friends_remove_confirm, profile.gamertag)
                        : getString(R.string.xbox_friends_add_confirm, profile.gamertag))
                .setPositiveButton(getString(label), v -> runFriendAction(remove))
                .setNegativeButton(getString(R.string.dialog_negative_cancel), null)
                .show();
    }

    private void runFriendAction(boolean remove) {
        progress.setVisibility(View.VISIBLE);
        XboxLiveClient.ActionCallback callback = new XboxLiveClient.ActionCallback() {
            @Override
            public void onDone() {
                Toast.makeText(XboxProfileActivity.this, remove
                        ? R.string.xbox_friends_removed : R.string.xbox_friends_added, Toast.LENGTH_SHORT).show();
                load();
            }

            @Override
            public void onError(String message) {
                progress.setVisibility(View.GONE);
                Toast.makeText(XboxProfileActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        };
        if (remove) {
            client.removeFriend(xuid, callback);
        } else {
            client.acceptRequest(xuid, callback);
        }
    }

    private void openPeople(int tab) {
        if (profile == null) return;
        Intent intent = new Intent(this, XboxPeopleListActivity.class);
        intent.putExtra(XboxPeopleListActivity.EXTRA_XUID, profile.xuid);
        intent.putExtra(XboxPeopleListActivity.EXTRA_TITLE,
                profile.gamertag == null ? "" : profile.gamertag);
        intent.putExtra(XboxPeopleListActivity.EXTRA_TAB, tab);
        startActivity(intent);
    }
}
