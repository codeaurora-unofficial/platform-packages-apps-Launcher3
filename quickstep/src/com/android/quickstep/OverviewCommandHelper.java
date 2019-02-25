/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.quickstep;

import static com.android.systemui.shared.system.ActivityManagerWrapper
        .CLOSE_SYSTEM_WINDOWS_REASON_RECENTS;

import android.animation.AnimatorSet;
import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.view.ViewConfiguration;

import com.android.launcher3.BaseDraggingActivity;
import com.android.launcher3.MainThreadExecutor;
import com.android.launcher3.logging.UserEventDispatcher;
import com.android.launcher3.userevent.nano.LauncherLogProto;
import com.android.quickstep.ActivityControlHelper.ActivityInitListener;
import com.android.quickstep.views.RecentsView;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.LatencyTrackerCompat;
import com.android.systemui.shared.system.RemoteAnimationTargetCompat;

/**
 * Helper class to handle various atomic commands for switching between Overview.
 */
@TargetApi(Build.VERSION_CODES.P)
public class OverviewCommandHelper {

    private final Context mContext;
    private final ActivityManagerWrapper mAM;
    private final RecentsModel mRecentsModel;
    private final MainThreadExecutor mMainThreadExecutor;
    private final OverviewComponentObserver mOverviewComponentObserver;

    private long mLastToggleTime;

    public OverviewCommandHelper(Context context, OverviewComponentObserver observer) {
        mContext = context;
        mAM = ActivityManagerWrapper.getInstance();
        mMainThreadExecutor = new MainThreadExecutor();
        mRecentsModel = RecentsModel.INSTANCE.get(mContext);
        mOverviewComponentObserver = observer;
    }

    public void onOverviewToggle() {
        // If currently screen pinning, do not enter overview
        if (mAM.isScreenPinningActive()) {
            return;
        }

        mAM.closeSystemWindows(CLOSE_SYSTEM_WINDOWS_REASON_RECENTS);
        mMainThreadExecutor.execute(new RecentsActivityCommand<>());
    }

    public void onOverviewShown() {
        mMainThreadExecutor.execute(new ShowRecentsCommand());
    }

    public void onTip(int actionType, int viewType) {
        mMainThreadExecutor.execute(() ->
                UserEventDispatcher.newInstance(mContext).logActionTip(actionType, viewType));
    }

    private class ShowRecentsCommand extends RecentsActivityCommand {

        @Override
        protected boolean handleCommand(long elapsedTime) {
            return mHelper.getVisibleRecentsView() != null;
        }
    }

    private class RecentsActivityCommand<T extends BaseDraggingActivity> implements Runnable {

        protected final ActivityControlHelper<T> mHelper;
        private final long mCreateTime;
        private final AppToOverviewAnimationProvider<T> mAnimationProvider;

        private final long mToggleClickedTime = SystemClock.uptimeMillis();
        private boolean mUserEventLogged;
        private ActivityInitListener mListener;

        public RecentsActivityCommand() {
            mHelper = mOverviewComponentObserver.getActivityControlHelper();
            mCreateTime = SystemClock.elapsedRealtime();
            mAnimationProvider =
                    new AppToOverviewAnimationProvider<>(mHelper, RecentsModel.getRunningTaskId());

            // Preload the plan
            mRecentsModel.getTasks(null);
        }

        @Override
        public void run() {
            long elapsedTime = mCreateTime - mLastToggleTime;
            mLastToggleTime = mCreateTime;

            if (handleCommand(elapsedTime)) {
                // Command already handled.
                return;
            }

            if (mHelper.switchToRecentsIfVisible(true)) {
                // If successfully switched, then return
                return;
            }

            // Otherwise, start overview.
            mListener = mHelper.createActivityInitListener(this::onActivityReady);
            mListener.registerAndStartActivity(mOverviewComponentObserver.getOverviewIntent(),
                    this::createWindowAnimation, mContext, mMainThreadExecutor.getHandler(),
                    mAnimationProvider.getRecentsLaunchDuration());
        }

        protected boolean handleCommand(long elapsedTime) {
            // TODO: We need to fix this case with PIP, when an activity first enters PIP, it shows
            //       the menu activity which takes window focus, preventing the right condition from
            //       being run below
            RecentsView recents = mHelper.getVisibleRecentsView();
            if (recents != null) {
                // Launch the next task
                recents.showNextTask();
                return true;
            } else if (elapsedTime < ViewConfiguration.getDoubleTapTimeout()) {
                // The user tried to launch back into overview too quickly, either after
                // launching an app, or before overview has actually shown, just ignore for now
                return true;
            }
            return false;
        }

        private boolean onActivityReady(T activity, Boolean wasVisible) {
            if (!mUserEventLogged) {
                activity.getUserEventDispatcher().logActionCommand(
                        LauncherLogProto.Action.Command.RECENTS_BUTTON,
                        mHelper.getContainerType(),
                        LauncherLogProto.ContainerType.TASKSWITCHER);
                mUserEventLogged = true;
            }
            return mAnimationProvider.onActivityReady(activity, wasVisible);
        }

        private AnimatorSet createWindowAnimation(RemoteAnimationTargetCompat[] targetCompats) {
            if (LatencyTrackerCompat.isEnabled(mContext)) {
                LatencyTrackerCompat.logToggleRecents(
                        (int) (SystemClock.uptimeMillis() - mToggleClickedTime));
            }

            mListener.unregister();

            return mAnimationProvider.createWindowAnimation(targetCompats);
        }
    }
}
