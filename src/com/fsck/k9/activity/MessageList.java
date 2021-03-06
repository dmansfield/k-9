package com.fsck.k9.activity;

import java.util.Collection;
import java.util.List;

import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences.Editor;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentManager.OnBackStackChangedListener;
import android.support.v4.app.FragmentTransaction;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.actionbarsherlock.app.ActionBar;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuItem;
import com.fsck.k9.Account;
import com.fsck.k9.Account.SortType;
import com.fsck.k9.K9;
import com.fsck.k9.K9.SplitViewMode;
import com.fsck.k9.Preferences;
import com.fsck.k9.R;
import com.fsck.k9.activity.misc.SwipeGestureDetector.OnSwipeGestureListener;
import com.fsck.k9.activity.setup.AccountSettings;
import com.fsck.k9.activity.setup.FolderSettings;
import com.fsck.k9.activity.setup.Prefs;
import com.fsck.k9.crypto.PgpData;
import com.fsck.k9.fragment.MessageListFragment;
import com.fsck.k9.fragment.MessageViewFragment;
import com.fsck.k9.fragment.MessageListFragment.MessageListFragmentListener;
import com.fsck.k9.fragment.MessageViewFragment.MessageViewFragmentListener;
import com.fsck.k9.mail.Message;
import com.fsck.k9.mail.store.StorageManager;
import com.fsck.k9.search.LocalSearch;
import com.fsck.k9.search.SearchAccount;
import com.fsck.k9.search.SearchSpecification;
import com.fsck.k9.search.SearchSpecification.Attribute;
import com.fsck.k9.search.SearchSpecification.Searchfield;
import com.fsck.k9.search.SearchSpecification.SearchCondition;
import com.fsck.k9.view.MessageHeader;
import com.fsck.k9.view.MessageTitleView;
import com.fsck.k9.view.ViewSwitcher;
import com.fsck.k9.view.ViewSwitcher.OnSwitchCompleteListener;

import de.cketti.library.changelog.ChangeLog;


/**
 * MessageList is the primary user interface for the program. This Activity
 * shows a list of messages.
 * From this Activity the user can perform all standard message operations.
 */
public class MessageList extends K9FragmentActivity implements MessageListFragmentListener,
        MessageViewFragmentListener, OnBackStackChangedListener, OnSwipeGestureListener,
        OnSwitchCompleteListener {

    // for this activity
    private static final String EXTRA_SEARCH = "search";
    private static final String EXTRA_NO_THREADING = "no_threading";

    private static final String ACTION_SHORTCUT = "shortcut";
    private static final String EXTRA_SPECIAL_FOLDER = "special_folder";

    private static final String EXTRA_MESSAGE_REFERENCE = "message_reference";

    // used for remote search
    public static final String EXTRA_SEARCH_ACCOUNT = "com.fsck.k9.search_account";
    private static final String EXTRA_SEARCH_FOLDER = "com.fsck.k9.search_folder";

    private static final String STATE_DISPLAY_MODE = "displayMode";
    private static final String STATE_MESSAGE_LIST_WAS_DISPLAYED = "messageListWasDisplayed";

    public static void actionDisplaySearch(Context context, SearchSpecification search,
            boolean noThreading, boolean newTask) {
        actionDisplaySearch(context, search, noThreading, newTask, true);
    }

    public static void actionDisplaySearch(Context context, SearchSpecification search,
            boolean noThreading, boolean newTask, boolean clearTop) {
        context.startActivity(
                intentDisplaySearch(context, search, noThreading, newTask, clearTop));
    }

    public static Intent intentDisplaySearch(Context context, SearchSpecification search,
            boolean noThreading, boolean newTask, boolean clearTop) {
        Intent intent = new Intent(context, MessageList.class);
        intent.putExtra(EXTRA_SEARCH, search);
        intent.putExtra(EXTRA_NO_THREADING, noThreading);

        if (clearTop) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        }
        if (newTask) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }

        return intent;
    }

    public static Intent shortcutIntent(Context context, String specialFolder) {
        Intent intent = new Intent(context, MessageList.class);
        intent.setAction(ACTION_SHORTCUT);
        intent.putExtra(EXTRA_SPECIAL_FOLDER, specialFolder);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        return intent;
    }

    public static Intent actionDisplayMessageIntent(Context context,
            MessageReference messageReference) {
        Intent intent = new Intent(context, MessageList.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra(EXTRA_MESSAGE_REFERENCE, messageReference);
        return intent;
    }

    public static Intent actionHandleNotificationIntent(Context context,
            MessageReference messageReference) {
        Intent intent = actionDisplayMessageIntent(context, messageReference);
        intent.setFlags(intent.getFlags() | Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }


    private enum DisplayMode {
        MESSAGE_LIST,
        MESSAGE_VIEW,
        SPLIT_VIEW
    }


    private StorageManager.StorageListener mStorageListener = new StorageListenerImplementation();

    private ActionBar mActionBar;
    private View mActionBarMessageList;
    private View mActionBarMessageView;
    private MessageTitleView mActionBarSubject;
    private TextView mActionBarTitle;
    private TextView mActionBarSubTitle;
    private TextView mActionBarUnread;
    private Menu mMenu;

    private ViewGroup mMessageViewContainer;
    private View mMessageViewPlaceHolder;

    private MessageListFragment mMessageListFragment;
    private MessageViewFragment mMessageViewFragment;

    private Account mAccount;
    private String mFolderName;
    private LocalSearch mSearch;
    private boolean mSingleFolderMode;
    private boolean mSingleAccountMode;
    private ProgressBar mActionBarProgress;
    private MenuItem mMenuButtonCheckMail;
    private View mActionButtonIndeterminateProgress;

    /**
     * {@code true} if the message list should be displayed as flat list (i.e. no threading)
     * regardless whether or not message threading was enabled in the settings. This is used for
     * filtered views, e.g. when only displaying the unread messages in a folder.
     */
    private boolean mNoThreading;

    private DisplayMode mDisplayMode;
    private MessageReference mMessageReference;

    /**
     * {@code true} when the message list was displayed once. This is used in
     * {@link #onBackPressed()} to decide whether to go from the message view to the message list or
     * finish the activity.
     */
    private boolean mMessageListWasDisplayed = false;
    private ViewSwitcher mViewSwitcher;


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (UpgradeDatabases.actionUpgradeDatabases(this, getIntent())) {
            finish();
            return;
        }

        if (useSplitView()) {
            setContentView(R.layout.split_message_list);
        } else {
            setContentView(R.layout.message_list);
            mViewSwitcher = (ViewSwitcher) findViewById(R.id.container);
            mViewSwitcher.setFirstInAnimation(AnimationUtils.loadAnimation(this, R.anim.slide_in_left));
            mViewSwitcher.setFirstOutAnimation(AnimationUtils.loadAnimation(this, R.anim.slide_out_right));
            mViewSwitcher.setSecondInAnimation(AnimationUtils.loadAnimation(this, R.anim.slide_in_right));
            mViewSwitcher.setSecondOutAnimation(AnimationUtils.loadAnimation(this, R.anim.slide_out_left));
            mViewSwitcher.setOnSwitchCompleteListener(this);
        }

        initializeActionBar();

        // Enable gesture detection for MessageLists
        setupGestureDetector(this);

        decodeExtras(getIntent());
        findFragments();
        initializeDisplayMode(savedInstanceState);
        initializeLayout();
        initializeFragments();
        displayViews();

        ChangeLog cl = new ChangeLog(this);
        if (cl.isFirstRun()) {
            cl.getLogDialog().show();
        }
    }

    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        setIntent(intent);

        removeMessageListFragment();
        removeMessageViewFragment();

        mMessageReference = null;
        mSearch = null;
        mFolderName = null;

        decodeExtras(intent);
        initializeDisplayMode(null);
        initializeFragments();
        displayViews();
    }

    /**
     * Get references to existing fragments if the activity was restarted.
     */
    private void findFragments() {
        FragmentManager fragmentManager = getSupportFragmentManager();
        mMessageListFragment = (MessageListFragment) fragmentManager.findFragmentById(
                R.id.message_list_container);
        mMessageViewFragment = (MessageViewFragment) fragmentManager.findFragmentById(
                R.id.message_view_container);
    }

    /**
     * Create fragment instances if necessary.
     *
     * @see #findFragments()
     */
    private void initializeFragments() {
        FragmentManager fragmentManager = getSupportFragmentManager();
        fragmentManager.addOnBackStackChangedListener(this);

        boolean hasMessageListFragment = (mMessageListFragment != null);

        if (!hasMessageListFragment) {
            FragmentTransaction ft = fragmentManager.beginTransaction();
            mMessageListFragment = MessageListFragment.newInstance(mSearch, false,
                    (K9.isThreadedViewEnabled() && !mNoThreading));
            ft.add(R.id.message_list_container, mMessageListFragment);
            ft.commit();
        }

        // Check if the fragment wasn't restarted and has a MessageReference in the arguments. If
        // so, open the referenced message.
        if (!hasMessageListFragment && mMessageViewFragment == null &&
                mMessageReference != null) {
            openMessage(mMessageReference);
        }
    }

    /**
     * Set the initial display mode (message list, message view, or split view).
     *
     * <p><strong>Note:</strong>
     * This method has to be called after {@link #findFragments()} because the result depends on
     * the availability of a {@link MessageViewFragment} instance.
     * </p>
     *
     * @param savedInstanceState
     *         The saved instance state that was passed to the activity as argument to
     *         {@link #onCreate(Bundle)}. May be {@code null}.
     */
    private void initializeDisplayMode(Bundle savedInstanceState) {
        if (useSplitView()) {
            mDisplayMode = DisplayMode.SPLIT_VIEW;
            return;
        }

        if (savedInstanceState != null) {
            DisplayMode savedDisplayMode =
                    (DisplayMode) savedInstanceState.getSerializable(STATE_DISPLAY_MODE);
            if (savedDisplayMode != DisplayMode.SPLIT_VIEW) {
                mDisplayMode = savedDisplayMode;
                return;
            }
        }

        if (mMessageViewFragment != null || mMessageReference != null) {
            mDisplayMode = DisplayMode.MESSAGE_VIEW;
        } else {
            mDisplayMode = DisplayMode.MESSAGE_LIST;
        }
    }

    private boolean useSplitView() {
        SplitViewMode splitViewMode = K9.getSplitViewMode();
        int orientation = getResources().getConfiguration().orientation;

        return (splitViewMode == SplitViewMode.ALWAYS ||
                (splitViewMode == SplitViewMode.WHEN_IN_LANDSCAPE &&
                orientation == Configuration.ORIENTATION_LANDSCAPE));
    }

    private void initializeLayout() {
        mMessageViewContainer = (ViewGroup) findViewById(R.id.message_view_container);
        mMessageViewPlaceHolder = getLayoutInflater().inflate(R.layout.empty_message_view, null);
    }

    private void displayViews() {
        switch (mDisplayMode) {
            case MESSAGE_LIST: {
                showMessageList();
                break;
            }
            case MESSAGE_VIEW: {
                showMessageView();
                break;
            }
            case SPLIT_VIEW: {
                mMessageListWasDisplayed = true;
                if (mMessageViewFragment == null) {
                    showMessageViewPlaceHolder();
                } else {
                    MessageReference activeMessage = mMessageViewFragment.getMessageReference();
                    if (activeMessage != null) {
                        mMessageListFragment.setActiveMessage(activeMessage);
                    }
                }
                break;
            }
        }
    }

    private void decodeExtras(Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_VIEW.equals(action)) {
            Uri uri = intent.getData();
            List<String> segmentList = uri.getPathSegments();

            String accountId = segmentList.get(0);
            Collection<Account> accounts = Preferences.getPreferences(this).getAvailableAccounts();
            for (Account account : accounts) {
                if (String.valueOf(account.getAccountNumber()).equals(accountId)) {
                    mMessageReference = new MessageReference();
                    mMessageReference.accountUuid = account.getUuid();
                    mMessageReference.folderName = segmentList.get(1);
                    mMessageReference.uid = segmentList.get(2);
                    break;
                }
            }
        } else if (ACTION_SHORTCUT.equals(action)) {
            // Handle shortcut intents
            String specialFolder = intent.getStringExtra(EXTRA_SPECIAL_FOLDER);
            if (SearchAccount.UNIFIED_INBOX.equals(specialFolder)) {
                mSearch = SearchAccount.createUnifiedInboxAccount(this).getRelatedSearch();
            } else if (SearchAccount.ALL_MESSAGES.equals(specialFolder)) {
                mSearch = SearchAccount.createAllMessagesAccount(this).getRelatedSearch();
            }
        } else if (intent.getStringExtra(SearchManager.QUERY) != null) {
            // check if this intent comes from the system search ( remote )
            if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
                //Query was received from Search Dialog
                String query = intent.getStringExtra(SearchManager.QUERY);

                mSearch = new LocalSearch(getString(R.string.search_results));
                mSearch.setManualSearch(true);
                mNoThreading = true;

                mSearch.or(new SearchCondition(Searchfield.SENDER, Attribute.CONTAINS, query));
                mSearch.or(new SearchCondition(Searchfield.SUBJECT, Attribute.CONTAINS, query));
                mSearch.or(new SearchCondition(Searchfield.MESSAGE_CONTENTS, Attribute.CONTAINS, query));

                Bundle appData = intent.getBundleExtra(SearchManager.APP_DATA);
                if (appData != null) {
                    mSearch.addAccountUuid(appData.getString(EXTRA_SEARCH_ACCOUNT));
                    // searches started from a folder list activity will provide an account, but no folder
                    if (appData.getString(EXTRA_SEARCH_FOLDER) != null) {
                        mSearch.addAllowedFolder(appData.getString(EXTRA_SEARCH_FOLDER));
                    }
                } else {
                    mSearch.addAccountUuid(LocalSearch.ALL_ACCOUNTS);
                }
            }
        } else {
            // regular LocalSearch object was passed
            mSearch = intent.getParcelableExtra(EXTRA_SEARCH);
            mNoThreading = intent.getBooleanExtra(EXTRA_NO_THREADING, false);
        }

        if (mMessageReference == null) {
            mMessageReference = intent.getParcelableExtra(EXTRA_MESSAGE_REFERENCE);
        }

        if (mMessageReference != null) {
            mSearch = new LocalSearch();
            mSearch.addAccountUuid(mMessageReference.accountUuid);
            mSearch.addAllowedFolder(mMessageReference.folderName);
        }

        String[] accountUuids = mSearch.getAccountUuids();
        mSingleAccountMode = (accountUuids.length == 1 && !mSearch.searchAllAccounts());
        mSingleFolderMode = mSingleAccountMode && (mSearch.getFolderNames().size() == 1);

        if (mSingleAccountMode) {
            Preferences prefs = Preferences.getPreferences(getApplicationContext());
            mAccount = prefs.getAccount(accountUuids[0]);

            if (mAccount != null && !mAccount.isAvailable(this)) {
                Log.i(K9.LOG_TAG, "not opening MessageList of unavailable account");
                onAccountUnavailable();
                return;
            }
        }

        if (mSingleFolderMode) {
            mFolderName = mSearch.getFolderNames().get(0);
        }

        // now we know if we are in single account mode and need a subtitle
        mActionBarSubTitle.setVisibility((!mSingleFolderMode) ? View.GONE : View.VISIBLE);
    }

    @Override
    public void onPause() {
        super.onPause();

        StorageManager.getInstance(getApplication()).removeListener(mStorageListener);
    }

    @Override
    public void onResume() {
        super.onResume();

        if (!(this instanceof Search)) {
            //necessary b/c no guarantee Search.onStop will be called before MessageList.onResume
            //when returning from search results
            Search.setActive(false);
        }

        if (mAccount != null && !mAccount.isAvailable(this)) {
            onAccountUnavailable();
            return;
        }
        StorageManager.getInstance(getApplication()).addListener(mStorageListener);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putSerializable(STATE_DISPLAY_MODE, mDisplayMode);
        outState.putBoolean(STATE_MESSAGE_LIST_WAS_DISPLAYED, mMessageListWasDisplayed);
    }

    @Override
    public void onRestoreInstanceState(Bundle savedInstanceState) {
        mMessageListWasDisplayed = savedInstanceState.getBoolean(STATE_MESSAGE_LIST_WAS_DISPLAYED);
    }

    private void initializeActionBar() {
        mActionBar = getSupportActionBar();

        mActionBar.setDisplayShowCustomEnabled(true);
        mActionBar.setCustomView(R.layout.actionbar_custom);

        View customView = mActionBar.getCustomView();
        mActionBarMessageList = customView.findViewById(R.id.actionbar_message_list);
        mActionBarMessageView = customView.findViewById(R.id.actionbar_message_view);
        mActionBarSubject = (MessageTitleView) customView.findViewById(R.id.message_title_view);
        mActionBarTitle = (TextView) customView.findViewById(R.id.actionbar_title_first);
        mActionBarSubTitle = (TextView) customView.findViewById(R.id.actionbar_title_sub);
        mActionBarUnread = (TextView) customView.findViewById(R.id.actionbar_unread_count);
        mActionBarProgress = (ProgressBar) customView.findViewById(R.id.actionbar_progress);
        mActionButtonIndeterminateProgress =
                getLayoutInflater().inflate(R.layout.actionbar_indeterminate_progress_actionview, null);

        mActionBar.setDisplayHomeAsUpEnabled(true);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        boolean ret = false;
        if (KeyEvent.ACTION_DOWN == event.getAction()) {
            ret = onCustomKeyDown(event.getKeyCode(), event);
        }
        if (!ret) {
            ret = super.dispatchKeyEvent(event);
        }
        return ret;
    }

    @Override
    public void onBackPressed() {
        if (mDisplayMode == DisplayMode.MESSAGE_VIEW && mMessageListWasDisplayed) {
            showMessageList();
        } else {
            super.onBackPressed();
        }
    }

    /**
     * Handle hotkeys
     *
     * <p>
     * This method is called by {@link #dispatchKeyEvent(KeyEvent)} before any view had the chance
     * to consume this key event.
     * </p>
     *
     * @param keyCode
     *         The value in {@code event.getKeyCode()}.
     * @param event
     *         Description of the key event.
     *
     * @return {@code true} if this event was consumed.
     */
    public boolean onCustomKeyDown(final int keyCode, final KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP: {
                if (mMessageViewFragment != null && mDisplayMode != DisplayMode.MESSAGE_LIST &&
                        K9.useVolumeKeysForNavigationEnabled()) {
                    showPreviousMessage();
                    return true;
                } else if (mDisplayMode != DisplayMode.MESSAGE_VIEW &&
                        K9.useVolumeKeysForListNavigationEnabled()) {
                    mMessageListFragment.onMoveUp();
                    return true;
                }

                break;
            }
            case KeyEvent.KEYCODE_VOLUME_DOWN: {
                if (mMessageViewFragment != null && mDisplayMode != DisplayMode.MESSAGE_LIST &&
                        K9.useVolumeKeysForNavigationEnabled()) {
                    showNextMessage();
                    return true;
                } else if (mDisplayMode != DisplayMode.MESSAGE_VIEW &&
                        K9.useVolumeKeysForListNavigationEnabled()) {
                    mMessageListFragment.onMoveDown();
                    return true;
                }

                break;
            }
            case KeyEvent.KEYCODE_C: {
                mMessageListFragment.onCompose();
                return true;
            }
            case KeyEvent.KEYCODE_Q: {
                onShowFolderList();
                return true;
            }
            case KeyEvent.KEYCODE_O: {
                mMessageListFragment.onCycleSort();
                return true;
            }
            case KeyEvent.KEYCODE_I: {
                mMessageListFragment.onReverseSort();
                return true;
            }
            case KeyEvent.KEYCODE_DEL:
            case KeyEvent.KEYCODE_D: {
                if (mDisplayMode == DisplayMode.MESSAGE_LIST) {
                    mMessageListFragment.onDelete();
                } else if (mMessageViewFragment != null) {
                    mMessageViewFragment.onDelete();
                }
                return true;
            }
            case KeyEvent.KEYCODE_S: {
                mMessageListFragment.toggleMessageSelect();
                return true;
            }
            case KeyEvent.KEYCODE_G: {
                if (mDisplayMode == DisplayMode.MESSAGE_LIST) {
                    mMessageListFragment.onToggleFlagged();
                } else if (mMessageViewFragment != null) {
                    mMessageViewFragment.onToggleFlagged();
                }
                return true;
            }
            case KeyEvent.KEYCODE_M: {
                if (mDisplayMode == DisplayMode.MESSAGE_LIST) {
                    mMessageListFragment.onMove();
                } else if (mMessageViewFragment != null) {
                    mMessageViewFragment.onMove();
                }
                return true;
            }
            case KeyEvent.KEYCODE_V: {
                if (mDisplayMode == DisplayMode.MESSAGE_LIST) {
                    mMessageListFragment.onArchive();
                } else if (mMessageViewFragment != null) {
                    mMessageViewFragment.onArchive();
                }
                return true;
            }
            case KeyEvent.KEYCODE_Y: {
                if (mDisplayMode == DisplayMode.MESSAGE_LIST) {
                    mMessageListFragment.onCopy();
                } else if (mMessageViewFragment != null) {
                    mMessageViewFragment.onCopy();
                }
                return true;
            }
            case KeyEvent.KEYCODE_Z: {
                if (mDisplayMode == DisplayMode.MESSAGE_LIST) {
                    mMessageListFragment.onToggleRead();
                } else if (mMessageViewFragment != null) {
                    mMessageViewFragment.onToggleRead();
                }
                return true;
            }
            case KeyEvent.KEYCODE_F: {
                if (mMessageViewFragment != null) {
                    mMessageViewFragment.onForward();
                }
                return true;
            }
            case KeyEvent.KEYCODE_A: {
                if (mMessageViewFragment != null) {
                    mMessageViewFragment.onReplyAll();
                }
                return true;
            }
            case KeyEvent.KEYCODE_R: {
                if (mMessageViewFragment != null) {
                    mMessageViewFragment.onReply();
                }
                return true;
            }
            case KeyEvent.KEYCODE_J:
            case KeyEvent.KEYCODE_P: {
                if (mMessageViewFragment != null) {
                    showPreviousMessage();
                }
                return true;
            }
            case KeyEvent.KEYCODE_N:
            case KeyEvent.KEYCODE_K: {
                if (mMessageViewFragment != null) {
                    showNextMessage();
                }
                return true;
            }
            /* FIXME
            case KeyEvent.KEYCODE_Z: {
                mMessageViewFragment.zoom(event);
                return true;
            }*/
            case KeyEvent.KEYCODE_H: {
                Toast toast = Toast.makeText(this, R.string.message_list_help_key, Toast.LENGTH_LONG);
                toast.show();
                return true;
            }

        }

        return false;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        // Swallow these events too to avoid the audible notification of a volume change
        if (K9.useVolumeKeysForListNavigationEnabled()) {
            if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP) || (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
                if (K9.DEBUG)
                    Log.v(K9.LOG_TAG, "Swallowed key up.");
                return true;
            }
        }
        return super.onKeyUp(keyCode, event);
    }

    private void onAccounts() {
        Accounts.listAccounts(this);
        finish();
    }

    private void onShowFolderList() {
        FolderList.actionHandleAccount(this, mAccount);
        finish();
    }

    private void onEditPrefs() {
        Prefs.actionPrefs(this);
    }

    private void onEditAccount() {
        AccountSettings.actionSettings(this, mAccount);
    }

    @Override
    public boolean onSearchRequested() {
        return mMessageListFragment.onSearchRequested();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        switch (itemId) {
            case android.R.id.home: {
                goBack();
                return true;
            }
            case R.id.compose: {
                mMessageListFragment.onCompose();
                return true;
            }
            case R.id.toggle_message_view_theme: {
                onToggleTheme();
                return true;
            }
            // MessageList
            case R.id.check_mail: {
                mMessageListFragment.checkMail();
                return true;
            }
            case R.id.set_sort_date: {
                mMessageListFragment.changeSort(SortType.SORT_DATE);
                return true;
            }
            case R.id.set_sort_arrival: {
                mMessageListFragment.changeSort(SortType.SORT_ARRIVAL);
                return true;
            }
            case R.id.set_sort_subject: {
                mMessageListFragment.changeSort(SortType.SORT_SUBJECT);
                return true;
            }
//            case R.id.set_sort_sender: {
//                mMessageListFragment.changeSort(SortType.SORT_SENDER);
//                return true;
//            }
            case R.id.set_sort_flag: {
                mMessageListFragment.changeSort(SortType.SORT_FLAGGED);
                return true;
            }
            case R.id.set_sort_unread: {
                mMessageListFragment.changeSort(SortType.SORT_UNREAD);
                return true;
            }
            case R.id.set_sort_attach: {
                mMessageListFragment.changeSort(SortType.SORT_ATTACHMENT);
                return true;
            }
            case R.id.select_all: {
                mMessageListFragment.selectAll();
                return true;
            }
            case R.id.app_settings: {
                onEditPrefs();
                return true;
            }
            case R.id.account_settings: {
                onEditAccount();
                return true;
            }
            case R.id.search: {
                mMessageListFragment.onSearchRequested();
                return true;
            }
            case R.id.search_remote: {
                mMessageListFragment.onRemoteSearch();
                return true;
            }
            // MessageView
            case R.id.next_message: {
                showNextMessage();
                return true;
            }
            case R.id.previous_message: {
                showPreviousMessage();
                return true;
            }
            case R.id.delete: {
                mMessageViewFragment.onDelete();
                return true;
            }
            case R.id.reply: {
                mMessageViewFragment.onReply();
                return true;
            }
            case R.id.reply_all: {
                mMessageViewFragment.onReplyAll();
                return true;
            }
            case R.id.forward: {
                mMessageViewFragment.onForward();
                return true;
            }
            case R.id.share: {
                mMessageViewFragment.onSendAlternate();
                return true;
            }
            case R.id.toggle_unread: {
                mMessageViewFragment.onToggleRead();
                return true;
            }
            case R.id.archive: {
                mMessageViewFragment.onArchive();
                return true;
            }
            case R.id.spam: {
                mMessageViewFragment.onSpam();
                return true;
            }
            case R.id.move: {
                mMessageViewFragment.onMove();
                return true;
            }
            case R.id.copy: {
                mMessageViewFragment.onCopy();
                return true;
            }
            case R.id.select_text: {
                mMessageViewFragment.onSelectText();
                return true;
            }
            case R.id.show_headers:
            case R.id.hide_headers: {
                mMessageViewFragment.onToggleAllHeadersView();
                updateMenu();
                return true;
            }
        }

        if (!mSingleFolderMode) {
            // None of the options after this point are "safe" for search results
            //TODO: This is not true for "unread" and "starred" searches in regular folders
            return false;
        }

        switch (itemId) {
            case R.id.send_messages: {
                mMessageListFragment.onSendPendingMessages();
                return true;
            }
            case R.id.folder_settings: {
                if (mFolderName != null) {
                    FolderSettings.actionSettings(this, mAccount, mFolderName);
                }
                return true;
            }
            case R.id.expunge: {
                mMessageListFragment.onExpunge();
                return true;
            }
            default: {
                return super.onOptionsItemSelected(item);
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getSupportMenuInflater().inflate(R.menu.message_list_option, menu);
        mMenu = menu;
        mMenuButtonCheckMail= menu.findItem(R.id.check_mail);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        configureMenu(menu);
        return true;
    }

    /**
     * Hide menu items not appropriate for the current context.
     *
     * <p><strong>Note:</strong>
     * Please adjust the comments in {@code res/menu/message_list_option.xml} if you change the
     * visibility of a menu item in this method.
     * </p>
     *
     * @param menu
     *         The {@link Menu} instance that should be modified. May be {@code null}; in that case
     *         the method does nothing and immediately returns.
     */
    private void configureMenu(Menu menu) {
        if (menu == null) {
            return;
        }

        // Set visibility of account/folder settings menu items
        if (mMessageListFragment == null) {
            menu.findItem(R.id.account_settings).setVisible(false);
            menu.findItem(R.id.folder_settings).setVisible(false);
        } else {
            menu.findItem(R.id.account_settings).setVisible(
                    mMessageListFragment.isSingleAccountMode());
            menu.findItem(R.id.folder_settings).setVisible(
                    mMessageListFragment.isSingleFolderMode());
        }

        /*
         * Set visibility of menu items related to the message view
         */

        if (mDisplayMode == DisplayMode.MESSAGE_LIST
                || mMessageViewFragment == null
                || !mMessageViewFragment.isInitialized()) {
            menu.findItem(R.id.next_message).setVisible(false);
            menu.findItem(R.id.previous_message).setVisible(false);
            menu.findItem(R.id.delete).setVisible(false);
            menu.findItem(R.id.single_message_options).setVisible(false);
            menu.findItem(R.id.refile).setVisible(false);
            menu.findItem(R.id.toggle_unread).setVisible(false);
            menu.findItem(R.id.select_text).setVisible(false);
            menu.findItem(R.id.toggle_message_view_theme).setVisible(false);
            menu.findItem(R.id.show_headers).setVisible(false);
            menu.findItem(R.id.hide_headers).setVisible(false);
        } else {
            // hide prev/next buttons in split mode
            if (mDisplayMode != DisplayMode.MESSAGE_VIEW) {
                menu.findItem(R.id.next_message).setVisible(false);
                menu.findItem(R.id.previous_message).setVisible(false);
            } else {
                MessageReference ref = mMessageViewFragment.getMessageReference();
                boolean initialized = (mMessageListFragment != null &&
                        mMessageListFragment.isLoadFinished());
                boolean canDoPrev = (initialized && !mMessageListFragment.isFirst(ref));
                boolean canDoNext = (initialized && !mMessageListFragment.isLast(ref));

                MenuItem prev = menu.findItem(R.id.previous_message);
                prev.setEnabled(canDoPrev);
                prev.getIcon().setAlpha(canDoPrev ? 255 : 127);

                MenuItem next = menu.findItem(R.id.next_message);
                next.setEnabled(canDoNext);
                next.getIcon().setAlpha(canDoNext ? 255 : 127);
            }

            MenuItem toggleTheme = menu.findItem(R.id.toggle_message_view_theme);
            if (K9.useFixedMessageViewTheme()) {
                toggleTheme.setVisible(false);
            } else {
                // Set title of menu item to switch to dark/light theme
                if (K9.getK9MessageViewTheme() == K9.Theme.DARK) {
                    toggleTheme.setTitle(R.string.message_view_theme_action_light);
                } else {
                    toggleTheme.setTitle(R.string.message_view_theme_action_dark);
                }
                toggleTheme.setVisible(true);
            }

            // Set title of menu item to toggle the read state of the currently displayed message
            if (mMessageViewFragment.isMessageRead()) {
                menu.findItem(R.id.toggle_unread).setTitle(R.string.mark_as_unread_action);
            } else {
                menu.findItem(R.id.toggle_unread).setTitle(R.string.mark_as_read_action);
            }

            menu.findItem(R.id.copy).setVisible(mMessageViewFragment.isCopyCapable());

            // Jellybean has built-in long press selection support
            menu.findItem(R.id.select_text).setVisible(Build.VERSION.SDK_INT < 16);

            if (mMessageViewFragment.isMoveCapable()) {
                menu.findItem(R.id.move).setVisible(true);
                menu.findItem(R.id.archive).setVisible(mMessageViewFragment.canMessageBeArchived());
                menu.findItem(R.id.spam).setVisible(mMessageViewFragment.canMessageBeMovedToSpam());
            } else {
                menu.findItem(R.id.move).setVisible(false);
                menu.findItem(R.id.archive).setVisible(false);
                menu.findItem(R.id.spam).setVisible(false);
            }

            if (mMessageViewFragment.allHeadersVisible()) {
                menu.findItem(R.id.show_headers).setVisible(false);
            } else {
                menu.findItem(R.id.hide_headers).setVisible(false);
            }

        }


        /*
         * Set visibility of menu items related to the message list
         */

        // Hide both search menu items by default and enable one when appropriate
        menu.findItem(R.id.search).setVisible(false);
        menu.findItem(R.id.search_remote).setVisible(false);

        if (mDisplayMode == DisplayMode.MESSAGE_VIEW || mMessageListFragment == null ||
                !mMessageListFragment.isInitialized()) {
            menu.findItem(R.id.check_mail).setVisible(false);
            menu.findItem(R.id.set_sort).setVisible(false);
            menu.findItem(R.id.select_all).setVisible(false);
            menu.findItem(R.id.send_messages).setVisible(false);
            menu.findItem(R.id.expunge).setVisible(false);
        } else {
            menu.findItem(R.id.set_sort).setVisible(true);
            menu.findItem(R.id.select_all).setVisible(true);

            if (!mMessageListFragment.isSingleAccountMode()) {
                menu.findItem(R.id.expunge).setVisible(false);
                menu.findItem(R.id.check_mail).setVisible(false);
                menu.findItem(R.id.send_messages).setVisible(false);
            } else {
                menu.findItem(R.id.send_messages).setVisible(mMessageListFragment.isOutbox());

                if (mMessageListFragment.isRemoteFolder()) {
                    menu.findItem(R.id.check_mail).setVisible(true);
                    menu.findItem(R.id.expunge).setVisible(
                            mMessageListFragment.isAccountExpungeCapable());
                } else {
                    menu.findItem(R.id.check_mail).setVisible(false);
                    menu.findItem(R.id.expunge).setVisible(false);
                }
            }

            // If this is an explicit local search, show the option to search on the server
            if (!mMessageListFragment.isRemoteSearch() &&
                    mMessageListFragment.isRemoteSearchAllowed()) {
                menu.findItem(R.id.search_remote).setVisible(true);
            } else if (!mMessageListFragment.isManualSearch()) {
                menu.findItem(R.id.search).setVisible(true);
            }
        }
    }

    protected void onAccountUnavailable() {
        finish();
        // TODO inform user about account unavailability using Toast
        Accounts.listAccounts(this);
    }

    public void setActionBarTitle(String title) {
        mActionBarTitle.setText(title);
    }

    public void setActionBarSubTitle(String subTitle) {
        mActionBarSubTitle.setText(subTitle);
    }

    public void setActionBarUnread(int unread) {
        if (unread == 0) {
            mActionBarUnread.setVisibility(View.GONE);
        } else {
            mActionBarUnread.setVisibility(View.VISIBLE);
            mActionBarUnread.setText(Integer.toString(unread));
        }
    }

    @Override
    public void setMessageListTitle(String title) {
        setActionBarTitle(title);
    }

    @Override
    public void setMessageListSubTitle(String subTitle) {
        setActionBarSubTitle(subTitle);
    }

    @Override
    public void setUnreadCount(int unread) {
        setActionBarUnread(unread);
    }

    @Override
    public void setMessageListProgress(int progress) {
        setSupportProgress(progress);
    }

    @Override
    public void openMessage(MessageReference messageReference) {
        Preferences prefs = Preferences.getPreferences(getApplicationContext());
        Account account = prefs.getAccount(messageReference.accountUuid);
        String folderName = messageReference.folderName;

        if (folderName.equals(account.getDraftsFolderName())) {
            MessageCompose.actionEditDraft(this, messageReference);
        } else {
            mMessageViewContainer.removeView(mMessageViewPlaceHolder);

            MessageViewFragment fragment = MessageViewFragment.newInstance(messageReference);
            FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
            ft.replace(R.id.message_view_container, fragment);
            mMessageViewFragment = fragment;
            ft.commit();

            if (mDisplayMode == DisplayMode.SPLIT_VIEW) {
                mMessageListFragment.setActiveMessage(messageReference);
            } else {
                showMessageView();
            }
        }
        invalidateOptionsMenu();
    }

    @Override
    public void onResendMessage(Message message) {
        MessageCompose.actionEditDraft(this, message.makeMessageReference());
    }

    @Override
    public void onForward(Message message) {
        MessageCompose.actionForward(this, message.getFolder().getAccount(), message, null);
    }

    @Override
    public void onReply(Message message) {
        MessageCompose.actionReply(this, message.getFolder().getAccount(), message, false, null);
    }

    @Override
    public void onReplyAll(Message message) {
        MessageCompose.actionReply(this, message.getFolder().getAccount(), message, true, null);
    }

    @Override
    public void onCompose(Account account) {
        MessageCompose.actionCompose(this, account);
    }

    @Override
    public void showMoreFromSameSender(String senderAddress) {
        LocalSearch tmpSearch = new LocalSearch("From " + senderAddress);
        tmpSearch.addAccountUuids(mSearch.getAccountUuids());
        tmpSearch.and(Searchfield.SENDER, senderAddress, Attribute.CONTAINS);

        MessageListFragment fragment = MessageListFragment.newInstance(tmpSearch, false, false);

        addMessageListFragment(fragment, true);
    }

    @Override
    public void onBackStackChanged() {
        FragmentManager fragmentManager = getSupportFragmentManager();
        mMessageListFragment = (MessageListFragment) fragmentManager.findFragmentById(
                R.id.message_list_container);
        mMessageViewFragment = (MessageViewFragment) fragmentManager.findFragmentById(
                R.id.message_view_container);

        if (mDisplayMode == DisplayMode.SPLIT_VIEW) {
            showMessageViewPlaceHolder();
        }

        configureMenu(mMenu);
    }

    @Override
    public void onSwipeRightToLeft(MotionEvent e1, MotionEvent e2) {
        if (mMessageListFragment != null && mDisplayMode != DisplayMode.MESSAGE_VIEW) {
            mMessageListFragment.onSwipeRightToLeft(e1, e2);
        }
    }

    @Override
    public void onSwipeLeftToRight(MotionEvent e1, MotionEvent e2) {
        if (mMessageListFragment != null && mDisplayMode != DisplayMode.MESSAGE_VIEW) {
            mMessageListFragment.onSwipeLeftToRight(e1, e2);
        }
    }

    private final class StorageListenerImplementation implements StorageManager.StorageListener {
        @Override
        public void onUnmount(String providerId) {
            if (mAccount != null && providerId.equals(mAccount.getLocalStorageProviderId())) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        onAccountUnavailable();
                    }
                });
            }
        }

        @Override
        public void onMount(String providerId) {
            // no-op
        }
    }

    private void addMessageListFragment(MessageListFragment fragment, boolean addToBackStack) {
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();

        ft.replace(R.id.message_list_container, fragment);
        if (addToBackStack)
            ft.addToBackStack(null);

        mMessageListFragment = fragment;
        ft.commit();
    }

    @Override
    public boolean startSearch(Account account, String folderName) {
        // If this search was started from a MessageList of a single folder, pass along that folder info
        // so that we can enable remote search.
        if (account != null && folderName != null) {
            final Bundle appData = new Bundle();
            appData.putString(EXTRA_SEARCH_ACCOUNT, account.getUuid());
            appData.putString(EXTRA_SEARCH_FOLDER, folderName);
            startSearch(null, false, appData, false);
        } else {
            // TODO Handle the case where we're searching from within a search result.
            startSearch(null, false, null, false);
        }

        return true;
    }

    @Override
    public void showThread(Account account, String folderName, long threadRootId) {
        showMessageViewPlaceHolder();

        LocalSearch tmpSearch = new LocalSearch();
        tmpSearch.addAccountUuid(account.getUuid());
        tmpSearch.and(Searchfield.THREAD_ID, String.valueOf(threadRootId), Attribute.EQUALS);

        MessageListFragment fragment = MessageListFragment.newInstance(tmpSearch, true, false);
        addMessageListFragment(fragment, true);
    }

    private void showMessageViewPlaceHolder() {
        removeMessageViewFragment();

        // Add placeholder view if necessary
        if (mMessageViewPlaceHolder.getParent() == null) {
            mMessageViewContainer.addView(mMessageViewPlaceHolder);
        }

        mMessageListFragment.setActiveMessage(null);
    }

    /**
     * Remove MessageViewFragment if necessary.
     */
    private void removeMessageViewFragment() {
        if (mMessageViewFragment != null) {
            FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
            ft.remove(mMessageViewFragment);
            mMessageViewFragment = null;
            ft.commit();

            showDefaultTitleView();
        }
    }

    private void removeMessageListFragment() {
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        ft.remove(mMessageListFragment);
        mMessageListFragment = null;
        ft.commit();
    }

    @Override
    public void remoteSearchStarted() {
        // Remove action button for remote search
        configureMenu(mMenu);
    }

    @Override
    public void goBack() {
        FragmentManager fragmentManager = getSupportFragmentManager();
        if (mDisplayMode == DisplayMode.MESSAGE_VIEW) {
            showMessageList();
        } else if (fragmentManager.getBackStackEntryCount() > 0) {
            fragmentManager.popBackStack();
        } else if (mMessageListFragment.isManualSearch()) {
            finish();
        } else if (!mSingleFolderMode) {
            onAccounts();
        } else {
            onShowFolderList();
        }
    }

    @Override
    public void enableActionBarProgress(boolean enable) {
        if (mMenuButtonCheckMail != null && mMenuButtonCheckMail.isVisible()) {
            mActionBarProgress.setVisibility(ProgressBar.GONE);
            if (enable) {
                mMenuButtonCheckMail
                        .setActionView(mActionButtonIndeterminateProgress);
            } else {
                mMenuButtonCheckMail.setActionView(null);
            }
        } else {
            if (mMenuButtonCheckMail != null)
                mMenuButtonCheckMail.setActionView(null);
            if (enable) {
                mActionBarProgress.setVisibility(ProgressBar.VISIBLE);
            } else {
                mActionBarProgress.setVisibility(ProgressBar.GONE);
            }
        }
    }

    private void restartActivity() {
        // restart the current activity, so that the theme change can be applied
        if (Build.VERSION.SDK_INT < 11) {
            Intent intent = getIntent();
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            finish();
            overridePendingTransition(0, 0); // disable animations to speed up the switch
            startActivity(intent);
            overridePendingTransition(0, 0);
        } else {
            recreate();
        }
    }

    @Override
    public void displayMessageSubject(String subject) {
        if (mDisplayMode == DisplayMode.MESSAGE_VIEW) {
            mActionBarSubject.setText(subject);
        }
    }

    @Override
    public void onReply(Message message, PgpData pgpData) {
        MessageCompose.actionReply(this, mAccount, message, false, pgpData.getDecryptedData());
        finish();
    }

    @Override
    public void onReplyAll(Message message, PgpData pgpData) {
        MessageCompose.actionReply(this, mAccount, message, true, pgpData.getDecryptedData());
        finish();
    }

    @Override
    public void onForward(Message mMessage, PgpData mPgpData) {
        MessageCompose.actionForward(this, mAccount, mMessage, mPgpData.getDecryptedData());
        finish();
    }

    @Override
    public void showNextMessageOrReturn() {
        if (K9.messageViewReturnToList() || !showNextMessage()) {
            if (mDisplayMode == DisplayMode.SPLIT_VIEW) {
                showMessageViewPlaceHolder();
            } else {
                showMessageList();
            }
        }
    }

    @Override
    public void setProgress(boolean enable) {
        setSupportProgressBarIndeterminateVisibility(enable);
    }

    @Override
    public void messageHeaderViewAvailable(MessageHeader header) {
        mActionBarSubject.setMessageHeader(header);
    }

    private boolean showNextMessage() {
        MessageReference ref = mMessageViewFragment.getMessageReference();
        if (ref != null) {
            if (mMessageListFragment.openNext(ref)) {
                return true;
            }
        }
        return false;
    }

    private boolean showPreviousMessage() {
        MessageReference ref = mMessageViewFragment.getMessageReference();
        if (ref != null) {
            if (mMessageListFragment.openPrevious(ref)) {
                return true;
            }
        }
        return false;
    }

    private void showMessageList() {
        mMessageListWasDisplayed = true;
        mDisplayMode = DisplayMode.MESSAGE_LIST;
        mViewSwitcher.showFirstView();

        mMessageListFragment.setActiveMessage(null);

        showDefaultTitleView();
        configureMenu(mMenu);
    }

    private void showMessageView() {
        mDisplayMode = DisplayMode.MESSAGE_VIEW;

        if (!mMessageListWasDisplayed) {
            mViewSwitcher.setAnimateFirstView(false);
        }
        mViewSwitcher.showSecondView();

        showMessageTitleView();
        configureMenu(mMenu);
    }

    @Override
    public void updateMenu() {
        invalidateOptionsMenu();
    }

    @Override
    public void disableDeleteAction() {
        mMenu.findItem(R.id.delete).setEnabled(false);
    }

    private void onToggleTheme() {
        if (K9.getK9MessageViewTheme() == K9.Theme.DARK) {
            K9.setK9MessageViewThemeSetting(K9.Theme.LIGHT);
        } else {
            K9.setK9MessageViewThemeSetting(K9.Theme.DARK);
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                Context appContext = getApplicationContext();
                Preferences prefs = Preferences.getPreferences(appContext);
                Editor editor = prefs.getPreferences().edit();
                K9.save(editor);
                editor.commit();
            }
        }).start();

        restartActivity();
    }

    private void showDefaultTitleView() {
        mActionBarMessageView.setVisibility(View.GONE);
        mActionBarMessageList.setVisibility(View.VISIBLE);

        if (mMessageListFragment != null) {
            mMessageListFragment.updateTitle();
        }

        mActionBarSubject.setMessageHeader(null);
    }

    private void showMessageTitleView() {
        mActionBarMessageList.setVisibility(View.GONE);
        mActionBarMessageView.setVisibility(View.VISIBLE);

        if (mMessageViewFragment != null) {
            displayMessageSubject(null);
            mMessageViewFragment.updateTitle();
        }
    }

    @Override
    public void onSwitchComplete(int displayedChild) {
        if (displayedChild == 0) {
            removeMessageViewFragment();
        }
    }
}
