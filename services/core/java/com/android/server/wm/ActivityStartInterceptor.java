/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.wm;

import static android.Manifest.permission.MANAGE_ACTIVITY_TASKS;
import static android.app.ActivityManager.INTENT_SENDER_ACTIVITY;
import static android.app.ActivityOptions.ANIM_OPEN_CROSS_PROFILE_APPS;
import static android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED;
import static android.app.PendingIntent.FLAG_CANCEL_CURRENT;
import static android.app.PendingIntent.FLAG_IMMUTABLE;
import static android.app.PendingIntent.FLAG_ONE_SHOT;
import static android.app.admin.DevicePolicyManager.EXTRA_RESTRICTION;
import static android.app.admin.DevicePolicyManager.POLICY_SUSPEND_PACKAGES;
import static android.content.Context.KEYGUARD_SERVICE;
import static android.content.Intent.ACTION_MAIN;
import static android.content.Intent.CATEGORY_HOME;
import static android.content.Intent.CATEGORY_SECONDARY_HOME;
import static android.content.Intent.EXTRA_INTENT;
import static android.content.Intent.EXTRA_PACKAGE_NAME;
import static android.content.Intent.EXTRA_TASK_ID;
import static android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.Intent.FLAG_ACTIVITY_TASK_ON_HOME;
import static android.content.pm.ApplicationInfo.FLAG_SUSPENDED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import static com.android.server.pm.PackageManagerService.PLATFORM_PACKAGE_NAME;

import android.annotation.Nullable;
import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.app.TaskInfo;
import android.app.admin.DevicePolicyManagerInternal;
import android.content.ComponentName;
import android.content.Context;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ResolveInfo;
import android.content.pm.SuspendDialogInfo;
import android.content.pm.UserInfo;
import android.content.pm.UserPackage;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.BlockedAppActivity;
import com.android.internal.app.HarmfulAppWarningActivity;
import com.android.internal.app.SuspendedAppActivity;
import com.android.internal.app.UnlaunchableAppActivity;
import com.android.server.LocalServices;
import com.android.server.am.ActivityManagerService;
import com.android.server.wm.ActivityInterceptorCallback.ActivityInterceptResult;

/**
 * A class that contains activity intercepting logic for {@link ActivityStarter#execute()}
 * It's initialized via setStates and interception occurs via the intercept method.
 *
 * Note that this class is instantiated when {@link ActivityManagerService} gets created so there
 * is no guarantee that other system services are already present.
 */
class ActivityStartInterceptor {
    private static final String TAG = "ActivityStartInterceptor";

    private final ActivityTaskManagerService mService;
    private final ActivityTaskSupervisor mSupervisor;
    private final Context mServiceContext;

    // UserManager cannot be final as it's not ready when this class is instantiated during boot
    private UserManager mUserManager;

    /*
     * Per-intent states loaded from ActivityStarter than shouldn't be changed by any
     * interception routines.
     */
    private int mRealCallingPid;
    private int mRealCallingUid;
    private int mUserId;
    private int mStartFlags;
    private String mCallingPackage;
    private @Nullable String mCallingFeatureId;

    /*
     * Per-intent states that were load from ActivityStarter and are subject to modifications
     * by the interception routines. After calling {@link #intercept} the caller should assign
     * these values back to {@link ActivityStarter#startActivityLocked}'s local variables if
     * {@link #intercept} returns true.
     */
    Intent mIntent;
    int mCallingPid;
    int mCallingUid;
    ResolveInfo mRInfo;
    ActivityInfo mAInfo;
    String mResolvedType;
    Task mInTask;
    TaskFragment mInTaskFragment;
    ActivityOptions mActivityOptions;

    /*
     * Note that this is just a hint of what the launch display area will be as it is
     * based only on the information at the early pre-interception stage of starting the
     * intent. The real launch display area calculated later may be different from this one.
     */
    TaskDisplayArea mPresumableLaunchDisplayArea;

    /**
     * Whether the component is specified originally in the given Intent.
     */
    boolean mComponentSpecified;

    ActivityStartInterceptor(
            ActivityTaskManagerService service, ActivityTaskSupervisor supervisor) {
        this(service, supervisor, service.mContext);
    }

    @VisibleForTesting
    ActivityStartInterceptor(ActivityTaskManagerService service, ActivityTaskSupervisor supervisor,
            Context context) {
        mService = service;
        mSupervisor = supervisor;
        mServiceContext = context;
    }

    /**
     * Effectively initialize the class before intercepting the start intent. The values set in this
     * method should not be changed during intercept.
     */
    void setStates(int userId, int realCallingPid, int realCallingUid, int startFlags,
            String callingPackage, @Nullable String callingFeatureId) {
        mRealCallingPid = realCallingPid;
        mRealCallingUid = realCallingUid;
        mUserId = userId;
        mStartFlags = startFlags;
        mCallingPackage = callingPackage;
        mCallingFeatureId = callingFeatureId;
    }

    private IntentSender createIntentSenderForOriginalIntent(int callingUid, int flags) {
        return createIntentSenderForOriginalIntent(callingUid, flags, Display.INVALID_DISPLAY);
    }

    private IntentSender createIntentSenderForOriginalIntent(int callingUid, int flags,
            int displayId) {
        ActivityOptions activityOptions = deferCrossProfileAppsAnimationIfNecessary();
        activityOptions.setPendingIntentCreatorBackgroundActivityStartMode(
                MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
        final TaskFragment taskFragment = getLaunchTaskFragment();
        // If the original intent is going to be embedded, try to forward the embedding TaskFragment
        // and its task id to embed back the original intent.
        if (taskFragment != null) {
            activityOptions.setLaunchTaskFragmentToken(taskFragment.getFragmentToken());
        }
        if (displayId != Display.INVALID_DISPLAY) {
            activityOptions.setLaunchDisplayId(displayId);
        }
        final IIntentSender target = mService.getIntentSenderLocked(
                INTENT_SENDER_ACTIVITY, mCallingPackage, mCallingFeatureId, callingUid, mUserId,
                null /*token*/, null /*resultCode*/, 0 /*requestCode*/,
                new Intent[] { mIntent }, new String[] { mResolvedType },
                flags, activityOptions.toBundle());
        return new IntentSender(target);
    }

    /**
     * A helper function to obtain the targeted {@link TaskFragment} during
     * {@link #intercept(Intent, ResolveInfo, ActivityInfo, String, Task, TaskFragment, int, int,
     * ActivityOptions, TaskDisplayArea)} if any.
     */
    @Nullable
    private TaskFragment getLaunchTaskFragment() {
        if (mInTaskFragment != null) {
            return mInTaskFragment;
        }
        if (mActivityOptions == null) {
            return null;
        }
        final IBinder taskFragToken = mActivityOptions.getLaunchTaskFragmentToken();
        if (taskFragToken == null) {
            return null;
        }
        return TaskFragment.fromTaskFragmentToken(taskFragToken, mService);
    }

    // TODO: consolidate this method with the one below since this is used for test only.
    boolean intercept(Intent intent, ResolveInfo rInfo, ActivityInfo aInfo, String resolvedType,
            Task inTask, TaskFragment inTaskFragment, int callingPid, int callingUid,
            ActivityOptions activityOptions, TaskDisplayArea presumableLaunchDisplayArea) {
        return intercept(intent, rInfo, aInfo, resolvedType, inTask, inTaskFragment, callingPid,
                callingUid, activityOptions, presumableLaunchDisplayArea, false);
    }

    /**
     * Intercept the launch intent based on various signals. If an interception happened the
     * internal variables get assigned and need to be read explicitly by the caller.
     *
     * @return true if an interception occurred
     */
    boolean intercept(Intent intent, ResolveInfo rInfo, ActivityInfo aInfo, String resolvedType,
            Task inTask, TaskFragment inTaskFragment, int callingPid, int callingUid,
            ActivityOptions activityOptions, TaskDisplayArea presumableLaunchDisplayArea,
            boolean componentSpecified) {
        mUserManager = UserManager.get(mServiceContext);

        mIntent = intent;
        mCallingPid = callingPid;
        mCallingUid = callingUid;
        mRInfo = rInfo;
        mAInfo = aInfo;
        mResolvedType = resolvedType;
        mInTask = inTask;
        mInTaskFragment = inTaskFragment;
        mActivityOptions = activityOptions;
        mPresumableLaunchDisplayArea = presumableLaunchDisplayArea;
        mComponentSpecified = componentSpecified;

        if (interceptQuietProfileIfNeeded()) {
            // If work profile is turned off, skip the work challenge since the profile can only
            // be unlocked when profile's user is running.
            return true;
        }
        if (interceptSuspendedPackageIfNeeded()) {
            // Skip the rest of interceptions as the package is suspended by device admin so
            // no user action can undo this.
            return true;
        }
        if (interceptLockTaskModeViolationPackageIfNeeded()) {
            return true;
        }
        if (interceptHarmfulAppIfNeeded()) {
            // If the app has a "harmful app" warning associated with it, we should ask to uninstall
            // before issuing the work challenge.
            return true;
        }
        if (interceptLockedProfileIfNeeded()) {
            return true;
        }
        if (interceptHomeIfNeeded()) {
            // Replace primary home intents directed at displays that do not support primary home
            // but support secondary home with the relevant secondary home activity. Or the home
            // intent is not in the correct format.
            return true;
        }

        if (interceptAutomatedPackageIfNeeded()) {
            // If the app is currently being automated, we should warn the user about it.
            return true;
        }

        if (interceptAppLaunchApproval()) return true;

        final SparseArray<ActivityInterceptorCallback> callbacks =
                mService.getActivityInterceptorCallbacks();
        final ActivityInterceptorCallback.ActivityInterceptorInfo interceptorInfo =
                getInterceptorInfo(null /* clearOptionsAnimation */);

        for (int i = 0; i < callbacks.size(); i++) {
            final ActivityInterceptorCallback callback = callbacks.valueAt(i);
            final ActivityInterceptResult interceptResult = callback.onInterceptActivityLaunch(
                    interceptorInfo);
            if (interceptResult == null) {
                continue;
            }
            mIntent = interceptResult.getIntent();
            mActivityOptions = interceptResult.getActivityOptions();
            mCallingPid = mRealCallingPid;
            mCallingUid = mRealCallingUid;
            // When an activity launch is intercepted, Intent#prepareToLeaveProcess is not called
            // since the interception happens in the system_server. So if any activity is calling
            // a trampoline activity, the keys do not get collected. Since all the interceptors
            // are present in the system_server, add the creator token before launching the
            // intercepted intent.
            mService.mAmInternal.addCreatorToken(mIntent, mCallingPackage);
            if (interceptResult.isActivityResolved()) {
                return true;
            }
            mRInfo = mSupervisor.resolveIntent(mIntent, null, mUserId, 0,
                    mRealCallingUid, mRealCallingPid);
            mAInfo = mSupervisor.resolveActivity(mIntent, mRInfo, mStartFlags,
                    null /*profilerInfo*/);
            return true;
        }
        return false;
    }

    @VisibleForTesting
    static final String APPROVAL_TOKEN = "android.avium.extra.LAUNCH_APPROVAL";
    private static final String APPROVAL_SETTING = "avium_app_launch_grants";
    @VisibleForTesting
    static final android.util.ArrayMap<IBinder, LaunchApproval> sLaunchApprovals =
            new android.util.ArrayMap<>();

    @VisibleForTesting
    static final class LaunchApproval {
        final int callerUid;
        final int targetUid;
        final Intent intent;
        final long expires = android.os.SystemClock.elapsedRealtime() + 60_000;
        boolean allowed;

        LaunchApproval(int callerUid, int targetUid, Intent intent) {
            this.callerUid = callerUid;
            this.targetUid = targetUid;
            this.intent = new Intent(intent);
        }
    }

    // Called with the activity-task manager lock. Only system-created, single-use capabilities
    // can authorize the deferred intent. A boolean extra supplied by an app is never trusted.
    @VisibleForTesting
    boolean consumeLaunchApproval() {
        final Bundle extras = mIntent.getBundleExtra(APPROVAL_TOKEN);
        if (extras == null) return false;
        final IBinder token = extras.getBinder("token");
        final LaunchApproval approval = sLaunchApprovals.get(token);
        mIntent.removeExtra(APPROVAL_TOKEN);
        if (approval == null || !approval.allowed || approval.callerUid != mCallingUid
                || approval.targetUid != mAInfo.applicationInfo.uid
                || approval.expires < android.os.SystemClock.elapsedRealtime()
                || !approval.intent.filterEquals(mIntent)) return false;
        sLaunchApprovals.remove(token);
        return true;
    }

    private String launchGrantKey(String source, int sourceUser, String target, int targetUser)
            throws android.content.pm.PackageManager.NameNotFoundException {
        final var pm = mServiceContext.getPackageManager();
        final var sourceInfo = pm.getPackageInfoAsUser(source,
                android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES, sourceUser);
        final var targetInfo = pm.getPackageInfoAsUser(target,
                android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES, targetUser);
        // Signer identity prevents a replacement package from inheriting another app's grant.
        final String identity = mUserManager.getSerialNumberForUser(UserHandle.of(sourceUser)) + ":" + source
                + ":" + android.util.PackageUtils.computeSignaturesSha256Digest(
                        sourceInfo.signingInfo.getApkContentsSigners())
                + ":" + mUserManager.getSerialNumberForUser(UserHandle.of(targetUser)) + ":" + target
                + ":" + android.util.PackageUtils.computeSignaturesSha256Digest(
                        targetInfo.signingInfo.getApkContentsSigners());
        return android.util.PackageUtils.computeSha256Digest(
                identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private boolean launchGrantStillMatches(String key, String source, int sourceUser,
            String target, int targetUser) {
        try {
            return key.equals(launchGrantKey(source, sourceUser, target, targetUser));
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** System-owned consent and picker surfaces already mediate the requested operation. */
    @VisibleForTesting
    static boolean isSystemLaunchFlow(ActivityInfo target, String permissionController,
            boolean platformSigned) {
        if (target == null || target.applicationInfo == null
                || !target.applicationInfo.isSystemApp()) return false;
        final String pkg = target.packageName;
        // Use the configured controller, including modular/vendor implementations.
        if (pkg != null && pkg.equals(permissionController)) return true;
        if (!platformSigned) return false;
        return "com.android.settings".equals(pkg)
                || "com.android.systemui".equals(pkg)
                || "com.android.documentsui".equals(pkg)
                || "com.google.android.documentsui".equals(pkg)
                || "com.android.packageinstaller".equals(pkg)
                || "com.google.android.packageinstaller".equals(pkg)
                || "com.android.providers.media.module".equals(pkg)
                || "com.google.android.providers.media.module".equals(pkg);
    }

    private boolean interceptAppLaunchApproval() {
        if (mIntent == null || mAInfo == null || mAInfo.applicationInfo == null
                || mCallingPackage == null) return false;
        if (consumeLaunchApproval()) return false;
        if (UserHandle.getAppId(mCallingUid) < android.os.Process.FIRST_APPLICATION_UID
                || mCallingPackage.equals(mAInfo.packageName)
                // SystemUI is the trusted host of TaskView bubbles. Its PendingIntent starts
                // the app inside a bubble task and must not be replaced by the approval UI.
                || "com.android.systemui".equals(mCallingPackage)
                || mServiceContext.checkPermission(MANAGE_ACTIVITY_TASKS, mCallingPid, mCallingUid)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
        // Launcher and recents starts are user actions, not an app requesting another app.
        final int sourceUser = UserHandle.getUserId(mCallingUid);
        final var pm = mServiceContext.getPackageManager();
        // Inspect the resolved destination, never an action string supplied by the caller.
        // Ordinary system apps (browser, camera, store, etc.) still require approval.
        if (isSystemLaunchFlow(mAInfo, pm.getPermissionControllerPackageName(),
                pm.checkSignatures("android", mAInfo.packageName)
                        == android.content.pm.PackageManager.SIGNATURE_MATCH)) return false;
        final Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        final ResolveInfo homeInfo = pm.resolveActivityAsUser(home, 0, sourceUser);
        if (homeInfo != null && homeInfo.activityInfo != null
                && mCallingPackage.equals(homeInfo.activityInfo.packageName)) return false;
        final long now = android.os.SystemClock.elapsedRealtime();
        for (int i = sLaunchApprovals.size() - 1; i >= 0; i--) {
            if (sLaunchApprovals.valueAt(i).expires < now) sLaunchApprovals.removeAt(i);
        }
        while (sLaunchApprovals.size() > 128) sLaunchApprovals.removeAt(0);
        final UserInfo parent = mUserManager.getProfileParent(sourceUser);
        final int owner = parent == null ? sourceUser : parent.id;
        final UserInfo sourceProfile = mUserManager.getUserInfo(sourceUser);
        final UserInfo destinationProfile = mUserManager.getUserInfo(mUserId);
        final boolean canChooseSpace = (sourceUser == owner || sourceProfile != null
                && (sourceProfile.isCloneProfile() || sourceProfile.isPrivateProfile()))
                && (mUserId == owner || destinationProfile != null
                && (destinationProfile.isCloneProfile() || destinationProfile.isPrivateProfile()));
        final android.content.ComponentName dialogComponent = new android.content.ComponentName(
                "org.avium.systemuiex",
                "org.avium.systemuiex.ui.selection.AppLaunchApprovalActivity");
        final Intent dialog = new Intent().setComponent(dialogComponent)
                .addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        final ResolveInfo dialogInfo = mSupervisor.resolveIntent(dialog, null, owner, 0,
                android.os.Process.SYSTEM_UID, android.os.Process.myPid());
        if (dialogInfo == null || dialogInfo.activityInfo == null) return false;

        final Intent original = new Intent(mIntent);
        final String targetPackage = mAInfo.packageName;
        final String sourcePackage = mCallingPackage;
        final int originalCaller = mCallingUid;
        final String featureId = mCallingFeatureId;
        final String resolvedType = mResolvedType;
        final ActivityOptions deferredOptions = mActivityOptions == null
                ? ActivityOptions.makeBasic() : ActivityOptions.fromBundle(mActivityOptions.toBundle());
        deferredOptions.setPendingIntentCreatorBackgroundActivityStartMode(
                MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
        final Bundle options = deferredOptions.toBundle();
        final java.util.ArrayList<IntentSender> senders = new java.util.ArrayList<>();
        final java.util.ArrayList<IBinder> tokens = new java.util.ArrayList<>();
        final java.util.ArrayList<String> keys = new java.util.ArrayList<>();
        final java.util.ArrayList<String> labels = new java.util.ArrayList<>();
        final java.util.ArrayList<Integer> spaces = new java.util.ArrayList<>();
        final java.util.ArrayList<Integer> targetUsers = new java.util.ArrayList<>();
        org.json.JSONArray grants;
        try {
            String stored = android.provider.Settings.Secure.getStringForUser(
                    mServiceContext.getContentResolver(), APPROVAL_SETTING, owner);
            grants = new org.json.JSONArray(stored == null ? "[]" : stored);
        } catch (org.json.JSONException e) {
            grants = new org.json.JSONArray();
        }
        for (UserInfo profile : mUserManager.getProfiles(owner)) {
            // Do not use the personal-space chooser to cross a managed-profile policy boundary.
            if (!canChooseSpace && profile.id != mUserId) continue;
            if (profile.id != mUserId && !profile.isCloneProfile() && !profile.isPrivateProfile()
                    && profile.id != owner) continue;
            if (!mUserManager.isUserUnlocked(profile.id)
                    || mUserManager.isQuietModeEnabled(profile.getUserHandle())) continue;
            Intent candidate = new Intent(original);
            if (profile.id != sourceUser) candidate.prepareToLeaveUser(sourceUser);
            // A selector participates in resolution but must not be combined with setPackage.
            final Intent lookup = candidate.getSelector() == null
                    ? new Intent(candidate) : new Intent(candidate.getSelector());
            lookup.setPackage(targetPackage);
            ResolveInfo resolved = mSupervisor.resolveIntent(lookup, resolvedType, profile.id,
                    0, originalCaller, mCallingPid);
            if (resolved == null || resolved.activityInfo == null
                    || !targetPackage.equals(resolved.activityInfo.packageName)) continue;
            try {
                final String key = launchGrantKey(sourcePackage, sourceUser,
                        targetPackage, profile.id);
                final IBinder token = new android.os.Binder();
                candidate.setSelector(null);
                candidate.setPackage(targetPackage);
                candidate.setComponent(new android.content.ComponentName(targetPackage,
                        resolved.activityInfo.name));
                final LaunchApproval approval = new LaunchApproval(originalCaller,
                        resolved.activityInfo.applicationInfo.uid, candidate);
                Bundle capability = new Bundle();
                capability.putBinder("token", token);
                candidate.putExtra(APPROVAL_TOKEN, capability);
                final var sender = mService.getIntentSenderLocked(INTENT_SENDER_ACTIVITY,
                        sourcePackage, featureId, originalCaller, profile.id, null, null,
                        System.identityHashCode(token), new Intent[] {candidate},
                        new String[] {resolvedType}, FLAG_ONE_SHOT | FLAG_IMMUTABLE, options);
                if (sender == null) continue;
                sLaunchApprovals.put(token, approval);
                senders.add(new IntentSender(sender));
                tokens.add(token);
                keys.add(key);
                targetUsers.add(profile.id);
                labels.add(resolved.loadLabel(pm).toString());
                spaces.add(profile.isCloneProfile() ? 1 : profile.isPrivateProfile() ? 2 : 0);
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                // Profile/package removal may race the chooser.
            }
        }
        if (senders.isEmpty()) return false;
        final boolean[] remembered = new boolean[keys.size()];
        for (int i = 0; i < keys.size(); i++) {
            for (int j = 0; j < grants.length(); j++) {
                remembered[i] |= keys.get(i).equals(grants.optString(j));
            }
        }
        // An existing grant only skips confirmation for the already resolved destination;
        // choosing another space must remain an explicit user decision.
        if (senders.size() == 1) {
            for (int i = 0; i < grants.length(); i++) {
                if (keys.get(0).equals(grants.optString(i)) && targetUsers.get(0) == mUserId) {
                    tokens.forEach(sLaunchApprovals::remove);
                    return false;
                }
            }
        }
        final android.os.RemoteCallback callback = new android.os.RemoteCallback(result -> {
            final int choice = result.getInt("choice", -1);
            final int action = result.getInt("action", 0);
            IntentSender sender = null;
            boolean persisted = false;
            synchronized (mService.mGlobalLock) {
                for (int i = 0; i < tokens.size(); i++) {
                    final LaunchApproval approval = sLaunchApprovals.get(tokens.get(i));
                    if (i == choice && (action == 1 || action == 2) && approval != null
                            && !approval.allowed && approval.expires >= android.os.SystemClock.elapsedRealtime()
                            && launchGrantStillMatches(keys.get(i), sourcePackage, sourceUser,
                                targetPackage, targetUsers.get(i))
                            && mUserManager.isUserUnlocked(targetUsers.get(i))
                            && !mUserManager.isQuietModeEnabled(UserHandle.of(targetUsers.get(i)))) {
                        approval.allowed = true;
                        sender = senders.get(i);
                        if (action == 2) {
                            try {
                                String saved = android.provider.Settings.Secure.getStringForUser(
                                        mServiceContext.getContentResolver(), APPROVAL_SETTING, owner);
                                org.json.JSONArray updated = new org.json.JSONArray(saved == null ? "[]" : saved);
                                boolean found = false;
                                for (int j = 0; j < updated.length(); j++) found |= keys.get(i).equals(updated.optString(j));
                                if (!found) updated.put(keys.get(i));
                                persisted = android.provider.Settings.Secure.putStringForUser(
                                        mServiceContext.getContentResolver(), APPROVAL_SETTING,
                                        updated.toString(), owner);
                            } catch (org.json.JSONException | IllegalArgumentException e) {
                                Slog.w(TAG, "Could not persist app launch grant", e);
                            }
                        }
                    } else sLaunchApprovals.remove(tokens.get(i));
                }
            }
            final android.os.RemoteCallback response = result.getParcelable(
                    "response", android.os.RemoteCallback.class);
            if (response != null) {
                Bundle approved = new Bundle();
                if (sender != null) approved.putParcelable("sender", sender);
                approved.putBoolean("persisted", persisted);
                response.sendResult(approved);
            }
        }, mService.mH);
        dialog.putExtra("labels", labels.toArray(new String[0]));
        dialog.putExtra("remembered", remembered);
        dialog.putExtra("spaces", spaces.stream().mapToInt(Integer::intValue).toArray());
        dialog.putExtra("callback", callback);
        try {
            dialog.putExtra("source", pm.getApplicationInfoAsUser(sourcePackage, 0, sourceUser).loadLabel(pm));
            dialog.putExtra("target", mAInfo.loadLabel(pm));
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            tokens.forEach(sLaunchApprovals::remove);
            return false;
        }
        mIntent = dialog;
        mInTask = null;
        mInTaskFragment = null;
        mActivityOptions = ActivityOptions.makeBasic();
        mRInfo = dialogInfo;
        mAInfo = dialogInfo.activityInfo;
        mResolvedType = null;
        // Match the platform's credential/harmful-app interceptors: retain attribution
        // to the real caller while the system substitutes a non-exported confirmation UI.
        mCallingUid = mRealCallingUid;
        mCallingPid = mRealCallingPid;
        return true;
    }

    private boolean hasCrossProfileAnimation() {
        return mActivityOptions != null
                && mActivityOptions.getAnimationType() == ANIM_OPEN_CROSS_PROFILE_APPS;
    }

    /**
     * If the activity option is the {@link ActivityOptions#ANIM_OPEN_CROSS_PROFILE_APPS} one,
     * defer the animation until the original intent is started.
     *
     * @return the activity option used to start the original intent.
     */
    private ActivityOptions deferCrossProfileAppsAnimationIfNecessary() {
        if (hasCrossProfileAnimation()) {
            mActivityOptions = null;
            return ActivityOptions.makeOpenCrossProfileAppsAnimation();
        }
        return ActivityOptions.makeBasic();
    }

    private boolean interceptQuietProfileIfNeeded() {
        // Do not intercept if the user has not turned off the profile
        if (!mUserManager.isQuietModeEnabled(UserHandle.of(mUserId))) {
            return false;
        }
        Slog.i(TAG, "Intent : " + mIntent + " intercepted for user: " + mUserId
                + " because quiet mode is enabled.");

        IntentSender target = createIntentSenderForOriginalIntent(mCallingUid,
                FLAG_CANCEL_CURRENT | FLAG_ONE_SHOT);

        mIntent = UnlaunchableAppActivity.createInQuietModeDialogIntent(mUserId, target, mRInfo);
        mCallingPid = mRealCallingPid;
        mCallingUid = mRealCallingUid;
        mResolvedType = null;

        final UserInfo parent = mUserManager.getProfileParent(mUserId);
        mRInfo = mSupervisor.resolveIntent(mIntent, mResolvedType, parent.id, 0,
                mRealCallingUid, mRealCallingPid);
        mAInfo = mSupervisor.resolveActivity(mIntent, mRInfo, mStartFlags, null /*profilerInfo*/);
        return true;
    }

    private boolean interceptSuspendedByAdminPackage() {
        DevicePolicyManagerInternal devicePolicyManager = LocalServices
                .getService(DevicePolicyManagerInternal.class);
        if (devicePolicyManager == null) {
            return false;
        }
        mIntent = devicePolicyManager.createShowAdminSupportIntent(mUserId, true);
        mIntent.putExtra(EXTRA_RESTRICTION, POLICY_SUSPEND_PACKAGES);

        mCallingPid = mRealCallingPid;
        mCallingUid = mRealCallingUid;
        mResolvedType = null;

        final UserInfo parent = mUserManager.getProfileParent(mUserId);
        if (parent != null) {
            mRInfo = mSupervisor.resolveIntent(mIntent, mResolvedType, parent.id, 0,
                    mRealCallingUid, mRealCallingPid);
        } else {
            mRInfo = mSupervisor.resolveIntent(mIntent, mResolvedType, mUserId, 0,
                    mRealCallingUid, mRealCallingPid);
        }
        mAInfo = mSupervisor.resolveActivity(mIntent, mRInfo, mStartFlags, null /*profilerInfo*/);
        return true;
    }

    private boolean interceptSuspendedPackageIfNeeded() {
        // Do not intercept if the package is not suspended
        if (!isPackageSuspended()) {
            return false;
        }
        final PackageManagerInternal pmi = mService.getPackageManagerInternalLocked();
        if (pmi == null) {
            return false;
        }
        final String suspendedPackage = mAInfo.applicationInfo.packageName;
        final UserPackage suspender = pmi.getSuspendingPackage(suspendedPackage, mUserId);
        if (suspender != null && PLATFORM_PACKAGE_NAME.equals(suspender.packageName)) {
            return interceptSuspendedByAdminPackage();
        }
        final SuspendDialogInfo dialogInfo = pmi.getSuspendedDialogInfo(suspendedPackage,
                suspender, mUserId);
        final Bundle crossProfileOptions = hasCrossProfileAnimation()
                ? ActivityOptions.makeOpenCrossProfileAppsAnimation().toBundle()
                : null;
        final IntentSender target = createIntentSenderForOriginalIntent(mCallingUid,
                FLAG_IMMUTABLE);
        mIntent = SuspendedAppActivity.createSuspendedAppInterceptIntent(suspendedPackage,
                suspender, dialogInfo, crossProfileOptions, target, mUserId);
        mCallingPid = mRealCallingPid;
        mCallingUid = mRealCallingUid;
        mResolvedType = null;
        mRInfo = mSupervisor.resolveIntent(mIntent, mResolvedType, mUserId, 0,
                mRealCallingUid, mRealCallingPid);
        mAInfo = mSupervisor.resolveActivity(mIntent, mRInfo, mStartFlags, null /*profilerInfo*/);
        return true;
    }

    private boolean interceptLockTaskModeViolationPackageIfNeeded() {
        if (mAInfo == null || mAInfo.applicationInfo == null) {
            return false;
        }
        LockTaskController controller = mService.getLockTaskController();
        String packageName = mAInfo.applicationInfo.packageName;
        int lockTaskLaunchMode = ActivityRecord.getLockTaskLaunchMode(mAInfo, mActivityOptions);
        if (controller.isActivityAllowed(mUserId, packageName, lockTaskLaunchMode)) {
            return false;
        }
        mIntent = BlockedAppActivity.createIntent(mUserId, mAInfo.applicationInfo.packageName);
        mCallingPid = mRealCallingPid;
        mCallingUid = mRealCallingUid;
        mResolvedType = null;
        mRInfo = mSupervisor.resolveIntent(mIntent, mResolvedType, mUserId, 0,
                mRealCallingUid, mRealCallingPid);
        mAInfo = mSupervisor.resolveActivity(mIntent, mRInfo, mStartFlags, null /*profilerInfo*/);
        return true;
    }

    private boolean interceptLockedProfileIfNeeded() {
        final Intent interceptingIntent = interceptWithConfirmCredentialsIfNeeded(mAInfo, mUserId);
        if (interceptingIntent == null) {
            return false;
        }
        mIntent = interceptingIntent;
        mCallingPid = mRealCallingPid;
        mCallingUid = mRealCallingUid;
        mResolvedType = null;
        final TaskFragment taskFragment = getLaunchTaskFragment();
        // If we are intercepting and there was a task, convert it into an extra for the
        // ConfirmCredentials intent and unassign it, as otherwise the task will move to
        // front even if ConfirmCredentials is cancelled.
        if (mInTask != null) {
            mIntent.putExtra(EXTRA_TASK_ID, mInTask.mTaskId);
            mInTask = null;
        } else if (taskFragment != null) {
            // If the original intent is started to an embedded TaskFragment, append its parent task
            // id to extra. It is to embed back the original intent to the TaskFragment with the
            // same task.
            final Task parentTask = taskFragment.getTask();
            if (parentTask != null) {
                mIntent.putExtra(EXTRA_TASK_ID, parentTask.mTaskId);
            }
        }
        if (mActivityOptions == null) {
            mActivityOptions = ActivityOptions.makeBasic();
        }

        final UserInfo parent = mUserManager.getProfileParent(mUserId);
        mRInfo = mSupervisor.resolveIntent(mIntent, mResolvedType, parent.id, 0,
                mRealCallingUid, mRealCallingPid);
        mAInfo = mSupervisor.resolveActivity(mIntent, mRInfo, mStartFlags, null /*profilerInfo*/);
        return true;
    }

    /**
     * Creates an intent to intercept the current activity start with Confirm Credentials if needed.
     *
     * @return The intercepting intent if needed.
     */
    private Intent interceptWithConfirmCredentialsIfNeeded(ActivityInfo aInfo, int userId) {
        if (!mService.mAmInternal.shouldConfirmCredentials(userId)) {
            return null;
        }
        if ((aInfo.flags & ActivityInfo.FLAG_SHOW_WHEN_LOCKED) != 0
                && (mUserManager.isUserUnlocked(userId) || aInfo.directBootAware)) {
            return null;
        }
        final IntentSender target = createIntentSenderForOriginalIntent(mCallingUid,
                FLAG_CANCEL_CURRENT | FLAG_ONE_SHOT | FLAG_IMMUTABLE);
        final KeyguardManager km = (KeyguardManager) mServiceContext
                .getSystemService(KEYGUARD_SERVICE);
        final Intent newIntent = km.createConfirmDeviceCredentialIntent(null, null, userId,
                true /* disallowBiometricsIfPolicyExists */);
        if (newIntent == null) {
            return null;
        }
        newIntent.setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS |
                FLAG_ACTIVITY_TASK_ON_HOME);
        newIntent.putExtra(EXTRA_PACKAGE_NAME, aInfo.packageName);
        newIntent.putExtra(EXTRA_INTENT, target);
        return newIntent;
    }

    private boolean interceptHarmfulAppIfNeeded() {
        CharSequence harmfulAppWarning;
        try {
            harmfulAppWarning = mService.getPackageManager()
                    .getHarmfulAppWarning(mAInfo.packageName, mUserId);
        } catch (RemoteException | IllegalArgumentException ex) {
            return false;
        }

        if (harmfulAppWarning == null) {
            return false;
        }

        final IntentSender target = createIntentSenderForOriginalIntent(mCallingUid,
                FLAG_CANCEL_CURRENT | FLAG_ONE_SHOT | FLAG_IMMUTABLE);

        mIntent = HarmfulAppWarningActivity.createHarmfulAppWarningIntent(mServiceContext,
                mAInfo.packageName, target, harmfulAppWarning);

        mCallingPid = mRealCallingPid;
        mCallingUid = mRealCallingUid;
        mResolvedType = null;

        mRInfo = mSupervisor.resolveIntent(mIntent, mResolvedType, mUserId, 0,
                mRealCallingUid, mRealCallingPid);
        mAInfo = mSupervisor.resolveActivity(mIntent, mRInfo, mStartFlags, null /*profilerInfo*/);
        return true;
    }

    private boolean interceptHomeIfNeeded() {
        if (mPresumableLaunchDisplayArea == null || mService.mRootWindowContainer == null) {
            return false;
        }

        boolean intercepted = false;
        if (!ACTION_MAIN.equals(mIntent.getAction()) || (!mIntent.hasCategory(CATEGORY_HOME)
                && !mIntent.hasCategory(CATEGORY_SECONDARY_HOME))) {
            // not a home intent
            return false;
        }

        if (mComponentSpecified) {
            Slog.w(TAG, "Starting home with component specified, uid=" + mCallingUid);
            if (mService.isCallerRecents(mCallingUid)
                    || ActivityTaskManagerService.checkPermission(MANAGE_ACTIVITY_TASKS,
                    mCallingPid, mCallingUid) == PERMISSION_GRANTED) {
                // Allow home component specified from trusted callers.
                return false;
            }

            final ComponentName homeComponent = mIntent.getComponent();
            final Intent homeIntent = mService.getHomeIntent();
            final ActivityInfo aInfo = mService.mRootWindowContainer.resolveHomeActivity(
                    mUserId, homeIntent);
            if (!aInfo.getComponentName().equals(homeComponent)) {
                // Do nothing if the intent is not for the default home component.
                return false;
            }
        }

        if (!ActivityRecord.isHomeIntent(mIntent) || mComponentSpecified) {
            // This is not a standard home intent, make it so if possible.
            normalizeHomeIntent();
            intercepted = true;
        }

        intercepted |= replaceToSecondaryHomeIntentIfNeeded();
        if (intercepted) {
            mCallingPid = mRealCallingPid;
            mCallingUid = mRealCallingUid;
            mResolvedType = null;

            mRInfo = mSupervisor.resolveIntent(mIntent, mResolvedType, mUserId, /* flags= */ 0,
                    mRealCallingUid, mRealCallingPid);
            mAInfo = mSupervisor.resolveActivity(mIntent, mRInfo, mStartFlags, /*profilerInfo=*/
                    null);
        }
        return intercepted;
    }

    private boolean interceptAutomatedPackageIfNeeded() {
        if (!android.companion.virtualdevice.flags.Flags.automatedAppLaunchInterception()) {
            return false;
        }
        if (mAInfo == null || mAInfo.packageName == null || mPresumableLaunchDisplayArea == null) {
            return false;
        }
        Intent intent = mSupervisor.createAutomatedAppLaunchWarningIntent(
                mAInfo.packageName, mUserId, mCallingPackage,
                mPresumableLaunchDisplayArea.getDisplayId());
        if (intent == null) {
            return false;
        }

        final IntentSender target = createIntentSenderForOriginalIntent(mCallingUid,
                FLAG_CANCEL_CURRENT | FLAG_ONE_SHOT | FLAG_IMMUTABLE,
                mPresumableLaunchDisplayArea.getDisplayId());

        mIntent = intent.putExtra(EXTRA_INTENT, target);

        mCallingPid = mRealCallingPid;
        mCallingUid = mRealCallingUid;
        mResolvedType = null;

        mRInfo = mSupervisor.resolveIntent(mIntent, mResolvedType, mUserId, 0,
                mRealCallingUid, mRealCallingPid);
        mAInfo = mSupervisor.resolveActivity(mIntent, mRInfo, mStartFlags, null /*profilerInfo*/);
        return true;
    }

    private void normalizeHomeIntent() {
        Slog.w(TAG, "The home Intent is not correctly formatted");
        if (mIntent.getCategories().size() > 1) {
            Slog.d(TAG, "Purge home intent categories");
            boolean isSecondaryHome = false;
            final Object[] categories = mIntent.getCategories().toArray();
            for (int i = categories.length - 1; i >= 0; i--) {
                final String category = (String) categories[i];
                if (CATEGORY_SECONDARY_HOME.equals(category)) {
                    isSecondaryHome = true;
                }
                mIntent.removeCategory(category);
            }
            mIntent.addCategory(isSecondaryHome ? CATEGORY_SECONDARY_HOME : CATEGORY_HOME);
        }
        if (mIntent.getType() != null || mIntent.getData() != null) {
            Slog.d(TAG, "Purge home intent data/type");
            mIntent.setType(null);
        }
        if (mComponentSpecified) {
            Slog.d(TAG, "Purge home intent component, " + mIntent.getComponent());
            mIntent.setComponent(null);
        }
        mIntent.addFlags(FLAG_ACTIVITY_NEW_TASK);
    }

    private boolean replaceToSecondaryHomeIntentIfNeeded() {
        if (!mIntent.hasCategory(Intent.CATEGORY_HOME)) {
            // Already a secondary home intent, leave it alone.
            return false;
        }
        if (mService.mRootWindowContainer.shouldPlacePrimaryHomeOnDisplay(
                mPresumableLaunchDisplayArea.getDisplayId())) {
            // Primary home can be launched to the display area.
            return false;
        }
        if (!mService.mRootWindowContainer.shouldPlaceSecondaryHomeOnDisplayArea(
                mPresumableLaunchDisplayArea)) {
            // Secondary home cannot be launched on the display area.
            return false;
        }

        // At this point we have a primary home intent for a display that does not support primary
        // home activity but it supports secondary home one. So replace it with secondary home.
        Pair<ActivityInfo, Intent> info = mService.mRootWindowContainer
                .resolveSecondaryHomeActivity(mUserId, mPresumableLaunchDisplayArea);
        mIntent = info.second;
        // The new task flag is needed because the home activity should already be in the root task
        // and should not be moved to the caller's task. Also, activities cannot change their type,
        // e.g. a standard activity cannot become a home activity.
        mIntent.addFlags(FLAG_ACTIVITY_NEW_TASK);
        return true;
    }

    private boolean isPackageSuspended() {
        return mAInfo != null && mAInfo.applicationInfo != null
                && (mAInfo.applicationInfo.flags & FLAG_SUSPENDED) != 0;
    }

    /**
     * Called when an activity is successfully launched.
     */
    void onActivityLaunched(TaskInfo taskInfo, ActivityRecord r) {
        final SparseArray<ActivityInterceptorCallback> callbacks =
                mService.getActivityInterceptorCallbacks();
        final ActivityInterceptorCallback.ActivityInterceptorInfo info = getInterceptorInfo(() -> {
            synchronized (mService.mGlobalLock) {
                r.clearOptionsAnimationForSiblings();
            }
        });
        for (int i = 0; i < callbacks.size(); i++) {
            final ActivityInterceptorCallback callback = callbacks.valueAt(i);
            callback.onActivityLaunched(taskInfo, r.info, info);
        }
    }

    private ActivityInterceptorCallback.ActivityInterceptorInfo getInterceptorInfo(
            @Nullable Runnable clearOptionsAnimation) {
        return new ActivityInterceptorCallback.ActivityInterceptorInfo.Builder(mCallingUid,
                mCallingPid, mRealCallingUid, mRealCallingPid, mUserId, mIntent, mRInfo, mAInfo)
                .setResolvedType(mResolvedType)
                .setCallingPackage(mCallingPackage)
                .setCallingFeatureId(mCallingFeatureId)
                .setCheckedOptions(mActivityOptions)
                .setClearOptionsAnimationRunnable(clearOptionsAnimation)
                .build();
    }

}
