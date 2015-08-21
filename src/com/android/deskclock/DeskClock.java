/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.deskclock;

import android.app.Fragment;
import android.app.FragmentManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.VisibleForTesting;
import android.support.design.widget.TabLayout;
import android.support.design.widget.TabLayout.Tab;
import android.support.design.widget.TabLayout.ViewPagerOnTabSelectedListener;
import android.support.v13.app.FragmentPagerAdapter;
import android.support.v4.view.ViewPager.OnPageChangeListener;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.ArraySet;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;

import com.android.deskclock.actionbarmenu.ActionBarMenuManager;
import com.android.deskclock.actionbarmenu.HelpMenuItemController;
import com.android.deskclock.actionbarmenu.MenuItemController;
import com.android.deskclock.actionbarmenu.NightModeMenuItemController;
import com.android.deskclock.actionbarmenu.SettingMenuItemController;
import com.android.deskclock.alarms.AlarmStateManager;
import com.android.deskclock.events.Events;
import com.android.deskclock.provider.Alarm;
import com.android.deskclock.settings.SettingsActivity;
import com.android.deskclock.stopwatch.StopwatchFragment;
import com.android.deskclock.stopwatch.StopwatchService;
import com.android.deskclock.stopwatch.Stopwatches;
import com.android.deskclock.timer.TimerFragment;
import com.android.deskclock.timer.TimerObj;
import com.android.deskclock.timer.Timers;
import com.android.deskclock.widget.RtlViewPager;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

/**
 * DeskClock clock view for desk docks.
 */
public class DeskClock extends BaseActivity
        implements LabelDialogFragment.TimerLabelDialogHandler,
        LabelDialogFragment.AlarmLabelDialogHandler {

    private static final String TAG = "DeskClock";

    // Alarm action for midnight (so we can update the date display).
    private static final String KEY_SELECTED_TAB = "selected_tab";
    public static final String SELECT_TAB_INTENT_EXTRA = "deskclock.select.tab";

    public static final int ALARM_TAB_INDEX = 0;
    public static final int CLOCK_TAB_INDEX = 1;
    public static final int TIMER_TAB_INDEX = 2;
    public static final int STOPWATCH_TAB_INDEX = 3;

    private final ActionBarMenuManager mActionBarMenuManager = new ActionBarMenuManager(this);
    private final MenuItemController nightModeMenuItemController =
            new NightModeMenuItemController(this);

    private TabLayout mTabLayout;
    private RtlViewPager mViewPager;
    private ImageView mFab;
    private ImageButton mLeftButton;
    private ImageButton mRightButton;

    private TabsAdapter mTabsAdapter;
    private int mSelectedTab;
    private boolean mActivityResumed;

    @Override
    public void onNewIntent(Intent newIntent) {
        super.onNewIntent(newIntent);
        LogUtils.d(TAG, "onNewIntent with intent: %s", newIntent);

        // update our intent so that we can consult it to determine whether or
        // not the most recent launch was via a dock event
        setIntent(newIntent);

        // Timer receiver may ask to go to the timers fragment if a timer expired.
        int tab = newIntent.getIntExtra(SELECT_TAB_INTENT_EXTRA, -1);
        if (tab != -1 && mTabLayout != null) {
            mTabLayout.getTabAt(tab).select();
            mViewPager.setCurrentItem(tab);
        }
    }

    private void initViews() {
        setContentView(R.layout.desk_clock);
        setSupportActionBar((Toolbar) findViewById(R.id.toolbar));
        mTabLayout = (TabLayout) findViewById(R.id.sliding_tabs);
        mFab = (ImageView) findViewById(R.id.fab);
        mLeftButton = (ImageButton) findViewById(R.id.left_button);
        mRightButton = (ImageButton) findViewById(R.id.right_button);
        if (mTabsAdapter == null) {
            mViewPager = (RtlViewPager) findViewById(R.id.desk_clock_pager);
            // Keep all four tabs to minimize jank.
            mViewPager.setOffscreenPageLimit(3);
            // Set Accessibility Delegate to null so ViewPager doesn't intercept movements and
            // prevent the fab from being selected.
            mViewPager.setAccessibilityDelegate(null);
            mTabsAdapter = new TabsAdapter(this, mViewPager);
            createTabs();
            mTabLayout.setOnTabSelectedListener(new ViewPagerOnTabSelectedListener(mViewPager));
        }

        mFab.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                getSelectedFragment().onFabClick(view);
            }
        });
        mLeftButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                getSelectedFragment().onLeftButtonClick(view);
            }
        });
        mRightButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                getSelectedFragment().onRightButtonClick(view);
            }
        });
    }

    @VisibleForTesting
    DeskClockFragment getSelectedFragment() {
        return (DeskClockFragment) mTabsAdapter.getItem(mSelectedTab);
    }

    private void createTabs() {
        final TabLayout.Tab alarmTab = mTabLayout.newTab();
        alarmTab.setIcon(R.drawable.ic_tab_alarm).setContentDescription(R.string.menu_alarm);
        mTabsAdapter.addTab(alarmTab, AlarmClockFragment.class, ALARM_TAB_INDEX);

        final Tab clockTab = mTabLayout.newTab();
        clockTab.setIcon(R.drawable.ic_tab_clock).setContentDescription(R.string.menu_clock);
        mTabsAdapter.addTab(clockTab, ClockFragment.class, CLOCK_TAB_INDEX);

        final Tab timerTab = mTabLayout.newTab();
        timerTab.setIcon(R.drawable.ic_tab_timer).setContentDescription(R.string.menu_timer);
        mTabsAdapter.addTab(timerTab, TimerFragment.class, TIMER_TAB_INDEX);

        final Tab stopwatchTab = mTabLayout.newTab();
        stopwatchTab.setIcon(R.drawable.ic_tab_stopwatch)
                .setContentDescription(R.string.menu_stopwatch);
        mTabsAdapter.addTab(stopwatchTab, StopwatchFragment.class, STOPWATCH_TAB_INDEX);

        mTabLayout.getTabAt(mSelectedTab).select();
        mViewPager.setCurrentItem(mSelectedTab);
        mTabsAdapter.notifySelectedPage(mSelectedTab);
    }

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        mActionBarMenuManager.addMenuController(new SettingMenuItemController(this))
                .addMenuController(new HelpMenuItemController(this))
                .addMenuController(nightModeMenuItemController);
        setVolumeControlStream(AudioManager.STREAM_ALARM);

        if (icicle != null) {
            mSelectedTab = icicle.getInt(KEY_SELECTED_TAB, CLOCK_TAB_INDEX);
        } else {
            mSelectedTab = CLOCK_TAB_INDEX;

            // Set the background color to initially match the theme value so that we can
            // smoothly transition to the dynamic color.
            setBackgroundColor(getResources().getColor(R.color.default_background),
                    false /* animate */);
        }

        // Timer receiver may ask the app to go to the timer fragment if a timer expired
        Intent i = getIntent();
        if (i != null) {
            int tab = i.getIntExtra(SELECT_TAB_INTENT_EXTRA, -1);
            if (tab != -1) {
                mSelectedTab = tab;
            }
        }
        initViews();
        setHomeTimeZone();

        // We need to update the system next alarm time on app startup because the
        // user might have clear our data.
        AlarmStateManager.updateNextAlarm(this);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // We only want to show notifications for stopwatch/timer when the app is closed so
        // that we don't have to worry about keeping the notifications in perfect sync with
        // the app.
        Intent stopwatchIntent = new Intent(getApplicationContext(), StopwatchService.class);
        stopwatchIntent.setAction(Stopwatches.KILL_NOTIF);
        startService(stopwatchIntent);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean(Timers.NOTIF_APP_OPEN, true);
        editor.apply();
        Utils.cancelTimesUpNotifications(this);
        Utils.updateTimesUpNotification(this);
        sendBroadcast(new Intent(Timers.NOTIF_IN_USE_CANCEL));
        mActivityResumed = true;
    }

    @Override
    public void onPause() {
        mActivityResumed = false;
        Intent intent = new Intent(getApplicationContext(), StopwatchService.class);
        intent.setAction(Stopwatches.SHOW_NOTIF);
        startService(intent);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean(Timers.NOTIF_APP_OPEN, false);
        editor.apply();
        Utils.showInUseNotifications(this);
        Utils.updateTimesUpNotification(this);

        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(KEY_SELECTED_TAB, mTabLayout.getSelectedTabPosition());
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        mActionBarMenuManager.createOptionsMenu(menu, getMenuInflater());
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);
        mActionBarMenuManager.prepareShowMenu(menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (mActionBarMenuManager.handleMenuItemClick(item)) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // Recreate the activity if any settings have been changed
        if (requestCode == SettingMenuItemController.REQUEST_CHANGE_SETTINGS
                && resultCode == RESULT_OK) {
            recreate();
        }
    }

    /**
     * Insert the local time zone as the Home Time Zone if one is not set
     */
    private void setHomeTimeZone() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String homeTimeZone = prefs.getString(SettingsActivity.KEY_HOME_TZ, "");
        if (!homeTimeZone.isEmpty()) {
            return;
        }
        homeTimeZone = TimeZone.getDefault().getID();
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(SettingsActivity.KEY_HOME_TZ, homeTimeZone);
        editor.apply();
        LogUtils.v(TAG, "Setting home time zone to " + homeTimeZone);
    }

    public void registerPageChangedListener(DeskClockFragment frag) {
        if (mTabsAdapter != null) {
            mTabsAdapter.registerPageChangedListener(frag);
        }
    }

    public void unregisterPageChangedListener(DeskClockFragment frag) {
        if (mTabsAdapter != null) {
            mTabsAdapter.unregisterPageChangedListener(frag);
        }
    }

    /**
     * Adapter for wrapping together the ActionBar's tab with the ViewPager
     */
    private class TabsAdapter extends FragmentPagerAdapter implements OnPageChangeListener {

        private static final String KEY_TAB_POSITION = "tab_position";

        final class TabInfo {
            private final Class<?> clss;
            private final Bundle args;

            TabInfo(Class<?> _class, int position) {
                clss = _class;
                args = new Bundle();
                args.putInt(KEY_TAB_POSITION, position);
            }

            public int getPosition() {
                return args.getInt(KEY_TAB_POSITION, 0);
            }
        }

        private final List<TabInfo> mTabs = new ArrayList<>(4 /* number of fragments */);
        private final Context mContext;
        private final RtlViewPager mPager;
        // Used for doing callbacks to fragments.
        private final Set<String> mFragmentTags = new ArraySet<>(4 /* number of fragments */);

        public TabsAdapter(AppCompatActivity activity, RtlViewPager pager) {
            super(activity.getFragmentManager());
            mContext = activity;
            mPager = pager;
            mPager.setAdapter(this);
            mPager.setOnRTLPageChangeListener(this);
        }

        @Override
        public Object instantiateItem(ViewGroup container, int position) {
            return super.instantiateItem(container, mViewPager.getRtlAwareIndex(position));
        }

        @Override
        public Fragment getItem(int position) {
            // Because this public method is called outside many times,
            // check if it exits first before creating a new one.
            final String name = makeFragmentName(R.id.desk_clock_pager, position);
            Fragment fragment = getFragmentManager().findFragmentByTag(name);
            if (fragment == null) {
                TabInfo info = mTabs.get(position);
                fragment = Fragment.instantiate(mContext, info.clss.getName(), info.args);
                if (fragment instanceof TimerFragment) {
                    ((TimerFragment) fragment).setFabAppearance();
                    ((TimerFragment) fragment).setLeftRightButtonAppearance();
                }
            }
            return fragment;
        }

        /**
         * Copied from:
         * android/frameworks/support/v13/java/android/support/v13/app/FragmentPagerAdapter.java#94
         * Create unique name for the fragment so fragment manager knows it exist.
         */
        private String makeFragmentName(int viewId, int index) {
            return "android:switcher:" + viewId + ":" + index;
        }

        @Override
        public int getCount() {
            return mTabs.size();
        }

        public void addTab(TabLayout.Tab tab, Class<?> clss, int position) {
            TabInfo info = new TabInfo(clss, position);
            mTabs.add(info);
            mTabLayout.addTab(tab);
            notifyDataSetChanged();
        }

        @Override
        public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
            // Do nothing
        }

        @Override
        public void onPageSelected(int position) {
            // Set the page before doing the menu so that onCreateOptionsMenu knows what page it is.
            mTabLayout.getTabAt(position).select();
            notifyPageChanged(position);

            mSelectedTab = position;
            nightModeMenuItemController.setEnabled(mSelectedTab == CLOCK_TAB_INDEX);

            if (mActivityResumed) {
                switch (mSelectedTab) {
                    case ALARM_TAB_INDEX:
                        Events.sendAlarmEvent(R.string.action_show, R.string.label_deskclock);
                        break;
                    case CLOCK_TAB_INDEX:
                        Events.sendClockEvent(R.string.action_show, R.string.label_deskclock);
                        break;
                    case TIMER_TAB_INDEX:
                        Events.sendTimerEvent(R.string.action_show, R.string.label_deskclock);
                        break;
                    case STOPWATCH_TAB_INDEX:
                        Events.sendStopwatchEvent(R.string.action_show, R.string.label_deskclock);
                        break;
                }
            }

            final DeskClockFragment f = (DeskClockFragment) getItem(position);
            if (f != null) {
                f.setFabAppearance();
                f.setLeftRightButtonAppearance();
            }
        }

        @Override
        public void onPageScrollStateChanged(int state) {
            // Do nothing
        }

        public void notifySelectedPage(int page) {
            notifyPageChanged(page);
        }

        private void notifyPageChanged(int newPage) {
            for (String tag : mFragmentTags) {
                final FragmentManager fm = getFragmentManager();
                DeskClockFragment f = (DeskClockFragment) fm.findFragmentByTag(tag);
                if (f != null) {
                    f.onPageChanged(newPage);
                }
            }
        }

        public void registerPageChangedListener(DeskClockFragment frag) {
            String tag = frag.getTag();
            if (mFragmentTags.contains(tag)) {
                LogUtils.wtf(TAG, "Trying to add an existing fragment " + tag);
            } else {
                mFragmentTags.add(frag.getTag());
            }
            // Since registering a listener by the fragment is done sometimes after the page
            // was already changed, make sure the fragment gets the current page
            frag.onPageChanged(mTabLayout.getSelectedTabPosition());
        }

        public void unregisterPageChangedListener(DeskClockFragment frag) {
            mFragmentTags.remove(frag.getTag());
        }
    }

    /**
     * Called by the LabelDialogFormat class after the dialog is finished. *
     */
    @Override
    public void onDialogLabelSet(TimerObj timer, String label, String tag) {
        Fragment frag = getFragmentManager().findFragmentByTag(tag);
        if (frag instanceof TimerFragment) {
            ((TimerFragment) frag).setLabel(timer, label);
        }
    }

    /**
     * Called by the LabelDialogFormat class after the dialog is finished. *
     */
    @Override
    public void onDialogLabelSet(Alarm alarm, String label, String tag) {
        Fragment frag = getFragmentManager().findFragmentByTag(tag);
        if (frag instanceof AlarmClockFragment) {
            ((AlarmClockFragment) frag).setLabel(alarm, label);
        }
    }

    public int getSelectedTab() {
        return mSelectedTab;
    }

    public ImageView getFab() {
        return mFab;
    }

    public ImageButton getLeftButton() {
        return mLeftButton;
    }

    public ImageButton getRightButton() {
        return mRightButton;
    }
}
