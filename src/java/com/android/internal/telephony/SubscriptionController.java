/*
* Copyright (C) 2014 The Android Open Source Project
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

package com.android.internal.telephony;

import android.content.BroadcastReceiver;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Binder;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.RadioAccessFamily;
import android.telephony.Rlog;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.text.format.Time;
import android.util.Log;

import com.android.internal.telephony.ITelephonyRegistry;
import com.android.internal.telephony.IccCardConstants.State;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

/**
 * SubscriptionController to provide an inter-process communication to
 * access Sms in Icc.
 *
 * Any setters which take subId, slotId or phoneId as a parameter will throw an exception if the
 * parameter equals the corresponding INVALID_XXX_ID or DEFAULT_XXX_ID.
 *
 * All getters will lookup the corresponding default if the parameter is DEFAULT_XXX_ID. Ie calling
 * getPhoneId(DEFAULT_SUB_ID) will return the same as getPhoneId(getDefaultSubId()).
 *
 * Finally, any getters which perform the mapping between subscriptions, slots and phones will
 * return the corresponding INVALID_XXX_ID if the parameter is INVALID_XXX_ID. All other getters
 * will fail and return the appropriate error value. Ie calling getSlotId(INVALID_SUBSCRIPTION_ID)
 * will return INVALID_SLOT_ID and calling getSubInfoForSubscriber(INVALID_SUBSCRIPTION_ID)
 * will return null.
 *
 */
public class SubscriptionController extends ISub.Stub {
    static final String LOG_TAG = "SubscriptionController";
    static final boolean DBG = true;
    static final boolean VDBG = false;
    static final int MAX_LOCAL_LOG_LINES = 500; // TODO: Reduce to 100 when 17678050 is fixed
    private ScLocalLog mLocalLog = new ScLocalLog(MAX_LOCAL_LOG_LINES);

    /**
     * Copied from android.util.LocalLog with flush() adding flush and line number
     * TODO: Update LocalLog
     */
    static class ScLocalLog {

        private LinkedList<String> mLog;
        private int mMaxLines;
        private Time mNow;

        public ScLocalLog(int maxLines) {
            mLog = new LinkedList<String>();
            mMaxLines = maxLines;
            mNow = new Time();
        }

        public synchronized void log(String msg) {
            if (mMaxLines > 0) {
                int pid = android.os.Process.myPid();
                int tid = android.os.Process.myTid();
                mNow.setToNow();
                mLog.add(mNow.format("%m-%d %H:%M:%S") + " pid=" + pid + " tid=" + tid + " " + msg);
                while (mLog.size() > mMaxLines) mLog.remove();
            }
        }

        public synchronized void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            final int LOOPS_PER_FLUSH = 10; // Flush every N loops.
            Iterator<String> itr = mLog.listIterator(0);
            int i = 0;
            while (itr.hasNext()) {
                pw.println(Integer.toString(i++) + ": " + itr.next());
                // Flush periodically so we don't drop lines
                if ((i % LOOPS_PER_FLUSH) == 0) pw.flush();
            }
        }
    }

    protected final Object mLock = new Object();
    protected boolean mSuccess;

    /** The singleton instance. */
    private static SubscriptionController sInstance = null;
    protected static PhoneProxy[] sProxyPhones;
    protected Context mContext;
    protected TelephonyManager mTelephonyManager;
    protected CallManager mCM;

    // FIXME: Does not allow for multiple subs in a slot and change to SparseArray
    private static HashMap<Integer, Integer> mSlotIdxToSubId = new HashMap<Integer, Integer>();
    private static int mDefaultVoiceSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    private static int mDefaultPhoneId = SubscriptionManager.DEFAULT_PHONE_INDEX;

    private static final int EVENT_WRITE_MSISDN_DONE = 1;

    private int[] colorArr;

    protected Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            AsyncResult ar;

            switch (msg.what) {
                case EVENT_WRITE_MSISDN_DONE:
                    ar = (AsyncResult) msg.obj;
                    synchronized (mLock) {
                        mSuccess = (ar.exception == null);
                        if (DBG) logd("EVENT_WRITE_MSISDN_DONE, mSuccess = "+mSuccess);
                        mLock.notifyAll();
                    }
                    break;
            }
        }
    };

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DBG) logd("onReceive " + intent);
            // TODO: Have GsmServiceStateTracker insert this data directly and deprecate
            // this broadcast.
            int subId = intent.getIntExtra(PhoneConstants.SUBSCRIPTION_KEY,
                    SubscriptionManager.INVALID_SUBSCRIPTION_ID);
            if (intent.getAction().equals(TelephonyIntents.SPN_STRINGS_UPDATED_ACTION)) {
                if (intent.getBooleanExtra(TelephonyIntents.EXTRA_SHOW_PLMN, false)) {
                    String carrierText = intent.getStringExtra(TelephonyIntents.EXTRA_PLMN);
                    if (intent.getBooleanExtra(TelephonyIntents.EXTRA_SPN, false)) {
                        // Need to show both plmn and spn.
                        String separator = mContext.getString(
                                com.android.internal.R.string.kg_text_message_separator).toString();
                        carrierText = new StringBuilder().append(carrierText).append(separator)
                                .append(intent.getStringExtra(TelephonyIntents.EXTRA_SPN))
                                .toString();
                    }
                    setCarrierText(carrierText, subId);
                } else if (intent.getBooleanExtra(TelephonyIntents.EXTRA_SHOW_SPN, false)) {
                    setCarrierText(intent.getStringExtra(TelephonyIntents.EXTRA_PLMN), subId);
                }
            }
        }
    };

    public static SubscriptionController init(Phone phone) {
        synchronized (SubscriptionController.class) {
            if (sInstance == null) {
                sInstance = new SubscriptionController(phone);
            } else {
                Log.wtf(LOG_TAG, "init() called multiple times!  sInstance = " + sInstance);
            }
            return sInstance;
        }
    }

    public static SubscriptionController init(Context c, CommandsInterface[] ci) {
        synchronized (SubscriptionController.class) {
            if (sInstance == null) {
                sInstance = new SubscriptionController(c);
            } else {
                Log.wtf(LOG_TAG, "init() called multiple times!  sInstance = " + sInstance);
            }
            return sInstance;
        }
    }

    public static SubscriptionController getInstance() {
        if (sInstance == null)
        {
           Log.wtf(LOG_TAG, "getInstance null");
        }

        return sInstance;
    }

    private SubscriptionController(Context c) {
        mContext = c;
        mCM = CallManager.getInstance();
        mTelephonyManager = TelephonyManager.from(mContext);

        if(ServiceManager.getService("isub") == null) {
                ServiceManager.addService("isub", this);
        }
        registerReceiverIfNeeded();

        if (DBG) logdl("[SubscriptionController] init by Context");
    }

    private boolean isSubInfoReady() {
        return mSlotIdxToSubId.size() > 0;
    }

    private SubscriptionController(Phone phone) {
        mContext = phone.getContext();
        mCM = CallManager.getInstance();

        if(ServiceManager.getService("isub") == null) {
                ServiceManager.addService("isub", this);
        }
        registerReceiverIfNeeded();

        if (DBG) logdl("[SubscriptionController] init by Phone");
    }

    private void registerReceiverIfNeeded() {
        // We only need to register the broadcast receiver if the URI
        // where we are going to store the data is valid.
        // TODO: This can be removed once the SubscriptionController is not running
        // on devices that don't need it, such as TVs.
        if (mContext.getPackageManager().resolveContentProvider(
                SubscriptionManager.CONTENT_URI.getAuthority(), 0) != null) {
            if (DBG) logd("registering SPN updated receiver");
            mContext.registerReceiver(mReceiver,
                    new IntentFilter(TelephonyIntents.SPN_STRINGS_UPDATED_ACTION));
        }
    }

    /**
     * Make sure the caller has the READ_PHONE_STATE permission.
     *
     * @throws SecurityException if the caller does not have the required permission
     */
    private void enforceSubscriptionPermission() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.READ_PHONE_STATE,
                "Requires READ_PHONE_STATE");
    }

    /**
     * Broadcast when SubscriptionInfo has changed
     * FIXME: Hopefully removed if the API council accepts SubscriptionInfoListener
     */
     private void broadcastSimInfoContentChanged() {
        Intent intent = new Intent(TelephonyIntents.ACTION_SUBINFO_CONTENT_CHANGE);
        mContext.sendBroadcast(intent);
        intent = new Intent(TelephonyIntents.ACTION_SUBINFO_RECORD_UPDATED);
        mContext.sendBroadcast(intent);
     }

     private boolean checkNotifyPermission(String method) {
         if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
                     == PackageManager.PERMISSION_GRANTED) {
             return true;
         }
         if (DBG) {
             logd("checkNotifyPermission Permission Denial: " + method + " from pid="
                     + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid());
         }
         return false;
     }

     public void notifySubscriptionInfoChanged() {
         if (!checkNotifyPermission("notifySubscriptionInfoChanged")) {
             return;
         }
         ITelephonyRegistry tr = ITelephonyRegistry.Stub.asInterface(ServiceManager.getService(
                 "telephony.registry"));
         try {
             if (DBG) logd("notifySubscriptionInfoChanged:");
             tr.notifySubscriptionInfoChanged();
         } catch (RemoteException ex) {
             // Should never happen because its always available.
         }

         // FIXME: Remove if listener technique accepted.
         broadcastSimInfoContentChanged();
     }

    /**
     * New SubInfoRecord instance and fill in detail info
     * @param cursor
     * @return the query result of desired SubInfoRecord
     */
    private SubscriptionInfo getSubInfoRecord(Cursor cursor) {
        int id = cursor.getInt(cursor.getColumnIndexOrThrow(
                SubscriptionManager.UNIQUE_KEY_SUBSCRIPTION_ID));
        String iccId = cursor.getString(cursor.getColumnIndexOrThrow(
                SubscriptionManager.ICC_ID));
        int simSlotIndex = cursor.getInt(cursor.getColumnIndexOrThrow(
                SubscriptionManager.SIM_SLOT_INDEX));
        String displayName = cursor.getString(cursor.getColumnIndexOrThrow(
                SubscriptionManager.DISPLAY_NAME));
        String carrierName = cursor.getString(cursor.getColumnIndexOrThrow(
                SubscriptionManager.CARRIER_NAME));
        int nameSource = cursor.getInt(cursor.getColumnIndexOrThrow(
                SubscriptionManager.NAME_SOURCE));
        int iconTint = cursor.getInt(cursor.getColumnIndexOrThrow(
                SubscriptionManager.COLOR));
        String number = cursor.getString(cursor.getColumnIndexOrThrow(
                SubscriptionManager.NUMBER));
        int dataRoaming = cursor.getInt(cursor.getColumnIndexOrThrow(
                SubscriptionManager.DATA_ROAMING));
        // Get the blank bitmap for this SubInfoRecord
        Bitmap iconBitmap = BitmapFactory.decodeResource(mContext.getResources(),
                com.android.internal.R.drawable.ic_sim_card_multi_24px_clr);
        int mcc = cursor.getInt(cursor.getColumnIndexOrThrow(
                SubscriptionManager.MCC));
        int mnc = cursor.getInt(cursor.getColumnIndexOrThrow(
                SubscriptionManager.MNC));
        // FIXME: consider stick this into database too
        String countryIso = getSubscriptionCountryIso(id);

        if (DBG) {
            logd("[getSubInfoRecord] id:" + id + " iccid:" + iccId + " simSlotIndex:" + simSlotIndex
                + " displayName:" + displayName + " nameSource:" + nameSource
                + " iconTint:" + iconTint + " number:" + number + " dataRoaming:" + dataRoaming
                + " mcc:" + mcc + " mnc:" + mnc + " countIso:" + countryIso);
        }

        return new SubscriptionInfo(id, iccId, simSlotIndex, displayName, carrierName,
                nameSource, iconTint, number, dataRoaming, iconBitmap, mcc, mnc, countryIso);
    }

    /**
     * Get ISO country code for the subscription's provider
     *
     * @param subId The subscription ID
     * @return The ISO country code for the subscription's provider
     */
    private String getSubscriptionCountryIso(int subId) {
        final int phoneId = getPhoneId(subId);
        if (phoneId < 0) {
            return "";
        }
        // FIXME: have a better way to get country code instead of reading from system property
        return TelephonyManager.getTelephonyProperty(
                phoneId, TelephonyProperties.PROPERTY_ICC_OPERATOR_ISO_COUNTRY, "");
    }

    /**
     * Query SubInfoRecord(s) from subinfo database
     * @param selection A filter declaring which rows to return
     * @param queryKey query key content
     * @return Array list of queried result from database
     */
     private List<SubscriptionInfo> getSubInfo(String selection, Object queryKey) {
        if (DBG) logd("selection:" + selection + " " + queryKey);
        String[] selectionArgs = null;
        if (queryKey != null) {
            selectionArgs = new String[] {queryKey.toString()};
        }
        ArrayList<SubscriptionInfo> subList = null;
        Cursor cursor = mContext.getContentResolver().query(SubscriptionManager.CONTENT_URI,
                null, selection, selectionArgs, null);
        try {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    SubscriptionInfo subInfo = getSubInfoRecord(cursor);
                    if (subInfo != null)
                    {
                        if (subList == null)
                        {
                            subList = new ArrayList<SubscriptionInfo>();
                        }
                        subList.add(subInfo);
                }
                }
            } else {
                if (DBG) logd("Query fail");
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        return subList;
    }

    /**
     * Find unused color to be set for new SubInfoRecord
     * @return RGB integer value of color
     */
    private int getUnusedColor() {
        List<SubscriptionInfo> availableSubInfos = getActiveSubscriptionInfoList();
        colorArr = mContext.getResources().getIntArray(com.android.internal.R.array.sim_colors);
        int colorIdx = 0;

        if (availableSubInfos != null) {
            for (int i = 0; i < colorArr.length; i++) {
                int j;
                for (j = 0; j < availableSubInfos.size(); j++) {
                    if (colorArr[i] == availableSubInfos.get(j).getIconTint()) {
                        break;
                    }
                }
                if (j == availableSubInfos.size()) {
                    return colorArr[i];
                }
            }
            colorIdx = availableSubInfos.size() % colorArr.length;
        }
        return colorArr[colorIdx];
    }

    /**
     * Get the active SubscriptionInfo with the subId key
     * @param subId The unique SubscriptionInfo key in database
     * @return SubscriptionInfo, maybe null if its not active
     */
    @Override
    public SubscriptionInfo getActiveSubscriptionInfo(int subId) {
        enforceSubscriptionPermission();

        List<SubscriptionInfo> subList = getActiveSubscriptionInfoList();
        for (SubscriptionInfo si : subList) {
            if (si.getSubscriptionId() == subId) {
                if (DBG) logd("[getActiveSubInfoForSubscriber]+ subId=" + subId + " subInfo=" + si);
                return si;
            }
        }
        if (DBG) logd("[getActiveSubInfoForSubscriber]+ subId=" + subId + " subInfo=null");
        return null;
    }

    /**
     * Get the active SubscriptionInfo associated with the iccId
     * @param iccId the IccId of SIM card
     * @return SubscriptionInfo, maybe null if its not active
     */
    @Override
    public SubscriptionInfo getActiveSubscriptionInfoForIccId(String iccId) {
        enforceSubscriptionPermission();

        List<SubscriptionInfo> subList = getActiveSubscriptionInfoList();
        for (SubscriptionInfo si : subList) {
            if (si.getIccId() == iccId) {
                if (DBG) logd("[getActiveSubInfoUsingIccId]+ iccId=" + iccId + " subInfo=" + si);
                return si;
            }
        }
        if (DBG) logd("[getActiveSubInfoUsingIccId]+ iccId=" + iccId + " subInfo=null");
        return null;
    }

    /**
     * Get the active SubscriptionInfo associated with the slotIdx
     * @param slotIdx the slot which the subscription is inserted
     * @return SubscriptionInfo, maybe null if its not active
     */
    @Override
    public SubscriptionInfo getActiveSubscriptionInfoForSimSlotIndex(int slotIdx) {
        enforceSubscriptionPermission();

        List<SubscriptionInfo> subList = getActiveSubscriptionInfoList();
        for (SubscriptionInfo si : subList) {
            if (si.getSimSlotIndex() == slotIdx) {
                if (DBG) {
                    logd("[getActiveSubscriptionInfoForSimSlotIndex]+ slotIdx=" + slotIdx
                        + " subId=" + si);
                }
                return si;
            }
        }
        if (DBG) {
            logd("[getActiveSubscriptionInfoForSimSlotIndex]+ slotIdx=" + slotIdx + " subId=null");
        }
        return null;
    }

    /**
     * @return List of all SubscriptionInfo records in database,
     * include those that were inserted before, maybe empty but not null.
     * @hide
     */
    @Override
    public List<SubscriptionInfo> getAllSubInfoList() {
        if (DBG) logd("[getAllSubInfoList]+");
        enforceSubscriptionPermission();

        List<SubscriptionInfo> subList = null;
        subList = getSubInfo(null, null);
        if (subList != null) {
            if (DBG) logd("[getAllSubInfoList]- " + subList.size() + " infos return");
        } else {
            if (DBG) logd("[getAllSubInfoList]- no info return");
        }

        return subList;
    }

    /**
     * Get the SubInfoRecord(s) of the currently inserted SIM(s)
     * @return Array list of currently inserted SubInfoRecord(s)
     */
    @Override
    public List<SubscriptionInfo> getActiveSubscriptionInfoList() {
        enforceSubscriptionPermission();
        if (DBG) logdl("[getActiveSubInfoList]+");

        List<SubscriptionInfo> subList = null;

        if (!isSubInfoReady()) {
            if (DBG) logdl("[getActiveSubInfoList] Sub Controller not ready");
            return subList;
        }

        subList = getSubInfo(SubscriptionManager.SIM_SLOT_INDEX + ">=0", null);
        if (subList != null) {
            // FIXME: Unnecessary when an insertion sort is used!
            Collections.sort(subList, new Comparator<SubscriptionInfo>() {
                @Override
                public int compare(SubscriptionInfo arg0, SubscriptionInfo arg1) {
                    // Primary sort key on SimSlotIndex
                    int flag = arg0.getSimSlotIndex() - arg1.getSimSlotIndex();
                    if (flag == 0) {
                        // Secondary sort on SubscriptionId
                        return arg0.getSubscriptionId() - arg1.getSubscriptionId();
                    }
                    return flag;
                }
            });

            if (DBG) logdl("[getActiveSubInfoList]- " + subList.size() + " infos return");
        } else {
            if (DBG) logdl("[getActiveSubInfoList]- no info return");
        }

        return subList;
    }

    /**
     * Get the SUB count of active SUB(s)
     * @return active SIM count
     */
    @Override
    public int getActiveSubInfoCount() {
        if (DBG) logd("[getActiveSubInfoCount]+");
        List<SubscriptionInfo> records = getActiveSubscriptionInfoList();
        if (records == null) {
            if (DBG) logd("[getActiveSubInfoCount] records null");
            return 0;
        }
        if (DBG) logd("[getActiveSubInfoCount]- count: " + records.size());
        return records.size();
    }

    /**
     * Get the SUB count of all SUB(s) in SubscriptoinInfo database
     * @return all SIM count in database, include what was inserted before
     */
    @Override
    public int getAllSubInfoCount() {
        if (DBG) logd("[getAllSubInfoCount]+");
        enforceSubscriptionPermission();

        Cursor cursor = mContext.getContentResolver().query(SubscriptionManager.CONTENT_URI,
                null, null, null, null);
        try {
            if (cursor != null) {
                int count = cursor.getCount();
                if (DBG) logd("[getAllSubInfoCount]- " + count + " SUB(s) in DB");
                return count;
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        if (DBG) logd("[getAllSubInfoCount]- no SUB in DB");

        return 0;
    }

    /**
     * @return the maximum number of subscriptions this device will support at any one time.
     */
    @Override
    public int getActiveSubInfoCountMax() {
        // FIXME: This valid now but change to use TelephonyDevController in the future
        return mTelephonyManager.getSimCount();
    }

    /**
     * Add a new SubInfoRecord to subinfo database if needed
     * @param iccId the IccId of the SIM card
     * @param slotId the slot which the SIM is inserted
     * @return 0 if success, < 0 on error.
     */
    @Override
    public int addSubInfoRecord(String iccId, int slotId) {
        if (DBG) logdl("[addSubInfoRecord]+ iccId:" + iccId + " slotId:" + slotId);
        enforceSubscriptionPermission();

        if (iccId == null) {
            if (DBG) logdl("[addSubInfoRecord]- null iccId");
            return -1;
        }

        int[] subIds = getSubId(slotId);
        if (subIds == null || subIds.length == 0) {
            if (DBG) {
                logdl("[addSubInfoRecord]- getSubId failed subIds == null || length == 0 subIds="
                    + subIds);
            }
            return -1;
        }

        String nameToSet;
        String CarrierName = TelephonyManager.getDefault().getSimOperator(subIds[0]);
        if (DBG) logdl("[addSubInfoRecord] CarrierName = " + CarrierName);
        String simCarrierName =
                TelephonyManager.getDefault().getSimOperatorName(subIds[0]);

        if (!TextUtils.isEmpty(simCarrierName)) {
            nameToSet = simCarrierName;
        } else {
            nameToSet = "CARD " + Integer.toString(slotId + 1);
        }
        if (DBG) logdl("[addSubInfoRecord] sim name = " + nameToSet);
        if (DBG) logdl("[addSubInfoRecord] carrier name = " + simCarrierName);

        ContentResolver resolver = mContext.getContentResolver();
        Cursor cursor = resolver.query(SubscriptionManager.CONTENT_URI,
                new String[] {SubscriptionManager.UNIQUE_KEY_SUBSCRIPTION_ID,
                SubscriptionManager.SIM_SLOT_INDEX, SubscriptionManager.NAME_SOURCE},
                SubscriptionManager.ICC_ID + "=?", new String[] {iccId}, null);

        int color = getUnusedColor();

        try {
            if (cursor == null || !cursor.moveToFirst()) {
                ContentValues value = new ContentValues();
                value.put(SubscriptionManager.ICC_ID, iccId);
                // default SIM color differs between slots
                value.put(SubscriptionManager.COLOR, color);
                value.put(SubscriptionManager.SIM_SLOT_INDEX, slotId);
                value.put(SubscriptionManager.DISPLAY_NAME, nameToSet);
                value.put(SubscriptionManager.CARRIER_NAME,
                        !TextUtils.isEmpty(simCarrierName) ? simCarrierName :
                        mContext.getString(com.android.internal.R.string.unknownName));
                Uri uri = resolver.insert(SubscriptionManager.CONTENT_URI, value);
                if (DBG) logdl("[addSubInfoRecord] New record created: " + uri);
            } else {
                int subId = cursor.getInt(0);
                int oldSimInfoId = cursor.getInt(1);
                int nameSource = cursor.getInt(2);
                ContentValues value = new ContentValues();

                if (slotId != oldSimInfoId) {
                    value.put(SubscriptionManager.SIM_SLOT_INDEX, slotId);
                }

                if (nameSource != SubscriptionManager.NAME_SOURCE_USER_INPUT) {
                    value.put(SubscriptionManager.DISPLAY_NAME, nameToSet);
                }

                if (!TextUtils.isEmpty(simCarrierName)) {
                    value.put(SubscriptionManager.CARRIER_NAME, simCarrierName);
                }

                if (value.size() > 0) {
                    resolver.update(SubscriptionManager.CONTENT_URI, value,
                            SubscriptionManager.UNIQUE_KEY_SUBSCRIPTION_ID +
                            "=" + Long.toString(subId), null);
                }

                if (DBG) logdl("[addSubInfoRecord] Record already exists");
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        cursor = resolver.query(SubscriptionManager.CONTENT_URI, null,
                SubscriptionManager.SIM_SLOT_INDEX + "=?",
                new String[] {String.valueOf(slotId)}, null);
        try {
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    int subId = cursor.getInt(cursor.getColumnIndexOrThrow(
                            SubscriptionManager.UNIQUE_KEY_SUBSCRIPTION_ID));
                    // If mSlotIdToSubIdMap already has a valid subId for a slotId/phoneId,
                    // do not add another subId for same slotId/phoneId.
                    Integer currentSubId = mSlotIdxToSubId.get(slotId);
                    if (currentSubId == null || !SubscriptionManager.isValidSubId(currentSubId)) {
                        // TODO While two subs active, if user deactivats first
                        // one, need to update the default subId with second one.

                        // FIXME: Currently we assume phoneId and slotId may not be true
                        // when we cross map modem or when multiple subs per slot.
                        // But is true at the moment.
                        mSlotIdxToSubId.put(slotId, subId);
                        int subIdCountMax = getActiveSubInfoCountMax();
                        int defaultSubId = getDefaultSubId();
                        if (DBG) {
                            logdl("[addSubInfoRecord]"
                                + " mSlotIdxToSubId.size=" + mSlotIdxToSubId.size()
                                + " slotId=" + slotId + " subId=" + subId
                                + " defaultSubId=" + defaultSubId + " simCount=" + subIdCountMax);
                        }

                        // Set the default sub if not set or if single sim device
                        if (!SubscriptionManager.isValidSubId(defaultSubId) || subIdCountMax == 1) {
                            setDefaultSubId(subId);
                        }
                        // If single sim device, set this subscription as the default for everything
                        if (subIdCountMax == 1) {
                            if (DBG) {
                                logdl("[addSubInfoRecord] one sim set defaults to subId=" + subId);
                            }
                            setDefaultDataSubId(subId);
                            setDefaultSmsSubId(subId);
                            setDefaultVoiceSubId(subId);
                        }
                    } else {
                        if (DBG) {
                            logdl("[addSubInfoRecord] currentSubId != null"
                                + " && currentSubId is valid, IGNORE");
                        }
                    }
                    if (DBG) logdl("[addSubInfoRecord] hashmap(" + slotId + "," + subId + ")");
                } while (cursor.moveToNext());
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        // Once the records are loaded, notify DcTracker
        updateAllDataConnectionTrackers();

        if (DBG) logdl("[addSubInfoRecord]- info size=" + mSlotIdxToSubId.size());
        return 0;
    }

    /**
     * Set carrier text by simInfo index
     * @param text new carrier text
     * @param subId the unique SubInfoRecord index in database
     * @return the number of records updated
     */
    private int setCarrierText(String text, int subId) {
        if (DBG) logd("[setCarrierText]+ text:" + text + " subId:" + subId);
        enforceSubscriptionPermission();

        ContentValues value = new ContentValues(1);
        value.put(SubscriptionManager.CARRIER_NAME, text);

        int result = mContext.getContentResolver().update(SubscriptionManager.CONTENT_URI, value,
                SubscriptionManager.UNIQUE_KEY_SUBSCRIPTION_ID + "=" + Long.toString(subId), null);
        notifySubscriptionInfoChanged();

        return result;
    }

    /**
     * Set SIM color tint by simInfo index
     * @param tint the tint color of the SIM
     * @param subId the unique SubInfoRecord index in database
     * @return the number of records updated
     */
    @Override
    public int setIconTint(int tint, int subId) {
        if (DBG) logd("[setIconTint]+ tint:" + tint + " subId:" + subId);
        enforceSubscriptionPermission();

        validateSubId(subId);
        ContentValues value = new ContentValues(1);
        value.put(SubscriptionManager.COLOR, tint);
        if (DBG) logd("[setIconTint]- tint:" + tint + " set");

        int result = mContext.getContentResolver().update(SubscriptionManager.CONTENT_URI, value,
                SubscriptionManager.UNIQUE_KEY_SUBSCRIPTION_ID + "=" + Long.toString(subId), null);
        notifySubscriptionInfoChanged();

        return result;
    }

    /**
     * Set display name by simInfo index
     * @param displayName the display name of SIM card
     * @param subId the unique SubInfoRecord index in database
     * @return the number of records updated
     */
    @Override
    public int setDisplayName(String displayName, int subId) {
        return setDisplayNameUsingSrc(displayName, subId, -1);
    }

    /**
     * Set display name by simInfo index with name source
     * @param displayName the display name of SIM card
     * @param subId the unique SubInfoRecord index in database
     * @param nameSource 0: NAME_SOURCE_DEFAULT_SOURCE, 1: NAME_SOURCE_SIM_SOURCE,
     *                   2: NAME_SOURCE_USER_INPUT, -1 NAME_SOURCE_UNDEFINED
     * @return the number of records updated
     */
    @Override
    public int setDisplayNameUsingSrc(String displayName, int subId, long nameSource) {
        if (DBG) {
            logd("[setDisplayName]+  displayName:" + displayName + " subId:" + subId
                + " nameSource:" + nameSource);
        }
        enforceSubscriptionPermission();

        validateSubId(subId);
        String nameToSet;
        if (displayName == null) {
            nameToSet = mContext.getString(SubscriptionManager.DEFAULT_NAME_RES);
        } else {
            nameToSet = displayName;
        }
        ContentValues value = new ContentValues(1);
        value.put(SubscriptionManager.DISPLAY_NAME, nameToSet);
        if (nameSource >= SubscriptionManager.NAME_SOURCE_DEFAULT_SOURCE) {
            if (DBG) logd("Set nameSource=" + nameSource);
            value.put(SubscriptionManager.NAME_SOURCE, nameSource);
        }
        if (DBG) logd("[setDisplayName]- mDisplayName:" + nameToSet + " set");

        int result = mContext.getContentResolver().update(SubscriptionManager.CONTENT_URI, value,
                SubscriptionManager.UNIQUE_KEY_SUBSCRIPTION_ID + "=" + Long.toString(subId), null);
        notifySubscriptionInfoChanged();

        return result;
    }

    /**
     * Set phone number by subId
     * @param number the phone number of the SIM
     * @param subId the unique SubInfoRecord index in database
     * @return the number of records updated
     */
    @Override
    public int setDisplayNumber(String number, int subId) {
        if (DBG) logd("[setDisplayNumber]+ number:" + number + " subId:" + subId);
        enforceSubscriptionPermission();

        validateSubId(subId);
        int result = 0;
        int phoneId = getPhoneId(subId);

        if (number == null || phoneId < 0 ||
                phoneId >= TelephonyManager.getDefault().getPhoneCount()) {
            if (DBG) logd("[setDispalyNumber]- fail");
            return -1;
        }
        ContentValues value = new ContentValues(1);
        value.put(SubscriptionManager.NUMBER, number);
        if (DBG) logd("[setDisplayNumber]- number:" + number + " set");

        Phone phone = sProxyPhones[phoneId];
        String alphaTag = TelephonyManager.getDefault().getLine1AlphaTagForSubscriber(subId);

        synchronized(mLock) {
            mSuccess = false;
            Message response = mHandler.obtainMessage(EVENT_WRITE_MSISDN_DONE);

            phone.setLine1Number(alphaTag, number, response);

            try {
                mLock.wait();
            } catch (InterruptedException e) {
                loge("interrupted while trying to write MSISDN");
            }
        }

        if (mSuccess) {
            result = mContext.getContentResolver().update(SubscriptionManager.CONTENT_URI, value,
                    SubscriptionManager.UNIQUE_KEY_SUBSCRIPTION_ID
                        + "=" + Long.toString(subId), null);
            if (DBG) logd("[setDisplayNumber]- update result :" + result);
            notifySubscriptionInfoChanged();
        }

        return result;
    }

    /**
     * Set data roaming by simInfo index
     * @param roaming 0:Don't allow data when roaming, 1:Allow data when roaming
     * @param subId the unique SubInfoRecord index in database
     * @return the number of records updated
     */
    @Override
    public int setDataRoaming(int roaming, int subId) {
        if (DBG) logd("[setDataRoaming]+ roaming:" + roaming + " subId:" + subId);
        enforceSubscriptionPermission();

        validateSubId(subId);
        if (roaming < 0) {
            if (DBG) logd("[setDataRoaming]- fail");
            return -1;
        }
        ContentValues value = new ContentValues(1);
        value.put(SubscriptionManager.DATA_ROAMING, roaming);
        if (DBG) logd("[setDataRoaming]- roaming:" + roaming + " set");

        int result = mContext.getContentResolver().update(SubscriptionManager.CONTENT_URI, value,
                SubscriptionManager.UNIQUE_KEY_SUBSCRIPTION_ID + "=" + Long.toString(subId), null);
        notifySubscriptionInfoChanged();

        return result;
    }

    /**
     * Set MCC/MNC by subscription ID
     * @param mccMnc MCC/MNC associated with the subscription
     * @param subId the unique SubInfoRecord index in database
     * @return the number of records updated
     */
    public int setMccMnc(String mccMnc, int subId) {
        int mcc = 0;
        int mnc = 0;
        try {
            mcc = Integer.parseInt(mccMnc.substring(0,3));
            mnc = Integer.parseInt(mccMnc.substring(3));
        } catch (NumberFormatException e) {
            loge("[setMccMnc] - couldn't parse mcc/mnc: " + mccMnc);
        }
        if (DBG) logd("[setMccMnc]+ mcc/mnc:" + mcc + "/" + mnc + " subId:" + subId);
        ContentValues value = new ContentValues(2);
        value.put(SubscriptionManager.MCC, mcc);
        value.put(SubscriptionManager.MNC, mnc);

        int result = mContext.getContentResolver().update(SubscriptionManager.CONTENT_URI, value,
                SubscriptionManager.UNIQUE_KEY_SUBSCRIPTION_ID + "=" + Long.toString(subId), null);
        notifySubscriptionInfoChanged();

        return result;
    }


    @Override
    public int getSlotId(int subId) {
        if (VDBG) printStackTrace("[getSlotId] subId=" + subId);

        if (subId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID) {
            subId = getDefaultSubId();
        }
        if (!SubscriptionManager.isValidSubId(subId)) {
            if (DBG) logd("[getSlotId]- subId invalid");
            return SubscriptionManager.INVALID_SIM_SLOT_INDEX;
        }

        int size = mSlotIdxToSubId.size();

        if (size == 0)
        {
            if (DBG) logd("[getSlotId]- size == 0, return SIM_NOT_INSERTED instead");
            return SubscriptionManager.SIM_NOT_INSERTED;
        }

        for (Entry<Integer, Integer> entry: mSlotIdxToSubId.entrySet()) {
            int sim = entry.getKey();
            int sub = entry.getValue();

            if (subId == sub)
            {
                if (VDBG) logv("[getSlotId]- return = " + sim);
                return sim;
            }
        }

        if (DBG) logd("[getSlotId]- return fail");
        return SubscriptionManager.INVALID_SIM_SLOT_INDEX;
    }

    /**
     * Return the subId for specified slot Id.
     * @deprecated
     */
    @Override
    @Deprecated
    public int[] getSubId(int slotIdx) {
        if (VDBG) printStackTrace("[getSubId]+ slotIdx=" + slotIdx);

        // Map default slotIdx to the current default subId.
        // TODO: Not used anywhere sp consider deleting as it's somewhat nebulous
        // as a slot maybe used for multiple different type of "connections"
        // such as: voice, data and sms. But we're doing the best we can and using
        // getDefaultSubId which makes a best guess.
        if (slotIdx == SubscriptionManager.DEFAULT_SIM_SLOT_INDEX) {
            slotIdx = getSlotId(getDefaultSubId());
            if (DBG) logd("[getSubId] map default slotIdx=" + slotIdx);
        }

        // Check that we have a valid SlotIdx
        if (!SubscriptionManager.isValidSlotId(slotIdx)) {
            if (DBG) logd("[getSubId]- invalid slotIdx=" + slotIdx);
            return null;
        }

        // Check if we've got any SubscriptionInfo records using slotIdToSubId as a surrogate.
        int size = mSlotIdxToSubId.size();
        if (size == 0) {
            if (DBG) {
                logd("[getSubId]- mSlotIdToSubIdMap.size == 0, return DummySubIds slotIdx="
                        + slotIdx);
            }
            return getDummySubIds(slotIdx);
        }

        // Create an array of subIds that are in this slot?
        ArrayList<Integer> subIds = new ArrayList<Integer>();
        for (Entry<Integer, Integer> entry: mSlotIdxToSubId.entrySet()) {
            int slot = entry.getKey();
            int sub = entry.getValue();
            if (slotIdx == slot) {
                subIds.add(sub);
            }
        }

        // Convert ArrayList to array
        int numSubIds = subIds.size();
        if (numSubIds > 0) {
            int[] subIdArr = new int[numSubIds];
            for (int i = 0; i < numSubIds; i++) {
                subIdArr[i] = subIds.get(i);
            }
            if (VDBG) logd("[getSubId]- subIdArr=" + subIdArr);
            return subIdArr;
        } else {
            if (DBG) logd("[getSubId]- numSubIds == 0, return DummySubIds slotIdx=" + slotIdx);
            return getDummySubIds(slotIdx);
        }
    }

    @Override
    public int getPhoneId(int subId) {
        if (VDBG) printStackTrace("[getPhoneId] subId=" + subId);
        int phoneId;

        if (subId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID) {
            subId = getDefaultSubId();
            if (DBG) logdl("[getPhoneId] asked for default subId=" + subId);
        }

        if (!SubscriptionManager.isValidSubId(subId)) {
            if (DBG) {
                logdl("[getPhoneId]- invalid subId return="
                        + SubscriptionManager.INVALID_PHONE_INDEX);
            }
            return SubscriptionManager.INVALID_PHONE_INDEX;
        }

        int size = mSlotIdxToSubId.size();
        if (size == 0) {
            phoneId = mDefaultPhoneId;
            if (DBG) logdl("[getPhoneId]- no sims, returning default phoneId=" + phoneId);
            return phoneId;
        }

        // FIXME: Assumes phoneId == slotId
        for (Entry<Integer, Integer> entry: mSlotIdxToSubId.entrySet()) {
            int sim = entry.getKey();
            int sub = entry.getValue();

            if (subId == sub) {
                if (VDBG) logdl("[getPhoneId]- found subId=" + subId + " phoneId=" + sim);
                return sim;
            }
        }

        phoneId = mDefaultPhoneId;
        if (DBG) {
            logdl("[getPhoneId]- subId=" + subId + " not found return default phoneId=" + phoneId);
        }
        return phoneId;

    }

    private int[] getDummySubIds(int slotIdx) {
        // FIXME: Remove notion of Dummy SUBSCRIPTION_ID.
        // I tested this returning null as no one appears to care,
        // but no connection came up on sprout with two sims.
        // We need to figure out why and hopefully remove DummySubsIds!!!
        int numSubs = getActiveSubInfoCountMax();
        if (numSubs > 0) {
            int[] dummyValues = new int[numSubs];
            for (int i = 0; i < numSubs; i++) {
                dummyValues[i] = SubscriptionManager.DUMMY_SUBSCRIPTION_ID_BASE - slotIdx;
            }
            if (DBG) {
                logd("getDummySubIds: slotIdx=" + slotIdx
                    + " return " + numSubs + " DummySubIds with each subId=" + dummyValues[0]);
            }
            return dummyValues;
        } else {
            return null;
        }
    }

    /**
     * @return the number of records cleared
     */
    @Override
    public int clearSubInfo() {
        enforceSubscriptionPermission();
        if (DBG) logd("[clearSubInfo]+");

        int size = mSlotIdxToSubId.size();

        if (size == 0) {
            if (DBG) logdl("[clearSubInfo]- no simInfo size=" + size);
            return 0;
        }

        mSlotIdxToSubId.clear();
        if (DBG) logdl("[clearSubInfo]- clear size=" + size);
        return size;
    }

    private void logvl(String msg) {
        logv(msg);
        mLocalLog.log(msg);
    }

    private void logv(String msg) {
        Rlog.v(LOG_TAG, msg);
    }

    private void logdl(String msg) {
        logd(msg);
        mLocalLog.log(msg);
    }

    private static void slogd(String msg) {
        Rlog.d(LOG_TAG, msg);
    }

    private void logd(String msg) {
        Rlog.d(LOG_TAG, msg);
    }

    private void logel(String msg) {
        loge(msg);
        mLocalLog.log(msg);
    }

    private void loge(String msg) {
        Rlog.e(LOG_TAG, msg);
    }

    @Override
    public int getDefaultSubId() {
        //FIXME: Make this smarter, need to handle data only and voice devices
        int subId = mDefaultVoiceSubId;
        if (VDBG) logv("[getDefaultSubId] value = " + subId);
        return subId;
    }

    @Override
    public void setDefaultSmsSubId(int subId) {
        if (subId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID) {
            throw new RuntimeException("setDefaultSmsSubId called with DEFAULT_SUB_ID");
        }
        if (DBG) logdl("[setDefaultSmsSubId] subId=" + subId);
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.MULTI_SIM_SMS_SUBSCRIPTION, subId);
        broadcastDefaultSmsSubIdChanged(subId);
    }

    private void broadcastDefaultSmsSubIdChanged(int subId) {
        // Broadcast an Intent for default sms sub change
        if (DBG) logdl("[broadcastDefaultSmsSubIdChanged] subId=" + subId);
        Intent intent = new Intent(TelephonyIntents.ACTION_DEFAULT_SMS_SUBSCRIPTION_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra(PhoneConstants.SUBSCRIPTION_KEY, subId);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    @Override
    public int getDefaultSmsSubId() {
        int subId = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.MULTI_SIM_SMS_SUBSCRIPTION,
                SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        if (VDBG) logd("[getDefaultSmsSubId] subId=" + subId);
        return subId;
    }

    @Override
    public void setDefaultVoiceSubId(int subId) {
        if (subId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID) {
            throw new RuntimeException("setDefaultVoiceSubId called with DEFAULT_SUB_ID");
        }
        if (DBG) logdl("[setDefaultVoiceSubId] subId=" + subId);
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.MULTI_SIM_VOICE_CALL_SUBSCRIPTION, subId);
        broadcastDefaultVoiceSubIdChanged(subId);
    }

    private void broadcastDefaultVoiceSubIdChanged(int subId) {
        // Broadcast an Intent for default voice sub change
        if (DBG) logdl("[broadcastDefaultVoiceSubIdChanged] subId=" + subId);
        Intent intent = new Intent(TelephonyIntents.ACTION_DEFAULT_VOICE_SUBSCRIPTION_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra(PhoneConstants.SUBSCRIPTION_KEY, subId);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    @Override
    public int getDefaultVoiceSubId() {
        int subId = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.MULTI_SIM_VOICE_CALL_SUBSCRIPTION,
                SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        if (VDBG) logd("[getDefaultVoiceSubId] subId=" + subId);
        return subId;
    }

    @Override
    public int getDefaultDataSubId() {
        int subId = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.MULTI_SIM_DATA_CALL_SUBSCRIPTION,
                SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        if (VDBG) logd("[getDefaultDataSubId] subId= " + subId);
        return subId;
    }

    @Override
    public void setDefaultDataSubId(int subId) {
        if (subId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID) {
            throw new RuntimeException("setDefaultDataSubId called with DEFAULT_SUB_ID");
        }
        if (DBG) logdl("[setDefaultDataSubId] subId=" + subId);

        int len = sProxyPhones.length;
        logdl("[setDefaultDataSubId] num phones=" + len);

        RadioAccessFamily[] rafs = new RadioAccessFamily[len];
        for (int phoneId = 0; phoneId < len; phoneId++) {
            PhoneProxy phone = sProxyPhones[phoneId];
            int raf = phone.getRadioAccessFamily();
            int id = phone.getSubId();
            logdl("[setDefaultDataSubId] phoneId=" + phoneId + " subId=" + id + " RAF=" + raf);
            // TODO(stuartscott): Need to set 3G or 2G depending on user's preference and modem
            // supported capabilities
            if (id == subId) {
                raf |= RadioAccessFamily.RAF_UMTS;
            } else {
                raf &= ~RadioAccessFamily.RAF_UMTS;
            }
            logdl("[setDefaultDataSubId] newRAF=" + raf);
            rafs[phoneId] = new RadioAccessFamily(phoneId, raf);
        }
        ProxyController.getInstance().setRadioCapability(rafs);

        // FIXME is this still needed?
        updateAllDataConnectionTrackers();

        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.MULTI_SIM_DATA_CALL_SUBSCRIPTION, subId);
        broadcastDefaultDataSubIdChanged(subId);
    }

    private void updateAllDataConnectionTrackers() {
        // Tell Phone Proxies to update data connection tracker
        int len = sProxyPhones.length;
        if (DBG) logdl("[updateAllDataConnectionTrackers] sProxyPhones.length=" + len);
        for (int phoneId = 0; phoneId < len; phoneId++) {
            if (DBG) logdl("[updateAllDataConnectionTrackers] phoneId=" + phoneId);
            sProxyPhones[phoneId].updateDataConnectionTracker();
        }
    }

    private void broadcastDefaultDataSubIdChanged(int subId) {
        // Broadcast an Intent for default data sub change
        if (DBG) logdl("[broadcastDefaultDataSubIdChanged] subId=" + subId);
        Intent intent = new Intent(TelephonyIntents.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra(PhoneConstants.SUBSCRIPTION_KEY, subId);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    /* Sets the default subscription. If only one sub is active that
     * sub is set as default subId. If two or more  sub's are active
     * the first sub is set as default subscription
     */
    // FIXME
    public void setDefaultSubId(int subId) {
        if (subId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID) {
            throw new RuntimeException("setDefaultSubId called with DEFAULT_SUB_ID");
        }
        if (DBG) logdl("[setDefaultSubId] subId=" + subId);
        if (SubscriptionManager.isValidSubId(subId)) {
            int phoneId = getPhoneId(subId);
            if (phoneId >= 0 && (phoneId < TelephonyManager.getDefault().getPhoneCount()
                    || TelephonyManager.getDefault().getSimCount() == 1)) {
                if (DBG) logdl("[setDefaultSubId] set mDefaultVoiceSubId=" + subId);
                mDefaultVoiceSubId = subId;
                // Update MCC MNC device configuration information
                String defaultMccMnc = TelephonyManager.getDefault().getSimOperator(phoneId);
                MccTable.updateMccMncConfiguration(mContext, defaultMccMnc, false);

                // Broadcast an Intent for default sub change
                Intent intent = new Intent(TelephonyIntents.ACTION_DEFAULT_SUBSCRIPTION_CHANGED);
                intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
                SubscriptionManager.putPhoneIdAndSubIdExtra(intent, phoneId, subId);
                if (VDBG) {
                    logdl("[setDefaultSubId] broadcast default subId changed phoneId=" + phoneId
                            + " subId=" + subId);
                }
                mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
            } else {
                if (VDBG) {
                    logdl("[setDefaultSubId] not set invalid phoneId=" + phoneId
                            + " subId=" + subId);
                }
            }
        }
    }

    @Override
    public void clearDefaultsForInactiveSubIds() {
        final List<SubscriptionInfo> records = getActiveSubscriptionInfoList();
        if (DBG) logdl("[clearDefaultsForInactiveSubIds] records: " + records);
        if (shouldDefaultBeCleared(records, getDefaultDataSubId())) {
            if (DBG) logd("[clearDefaultsForInactiveSubIds] clearing default data sub id");
            setDefaultDataSubId(SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        }
        if (shouldDefaultBeCleared(records, getDefaultSmsSubId())) {
            if (DBG) logdl("[clearDefaultsForInactiveSubIds] clearing default sms sub id");
            setDefaultSmsSubId(SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        }
        if (shouldDefaultBeCleared(records, getDefaultVoiceSubId())) {
            if (DBG) logdl("[clearDefaultsForInactiveSubIds] clearing default voice sub id");
            setDefaultVoiceSubId(SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        }
    }

    private boolean shouldDefaultBeCleared(List<SubscriptionInfo> records, int subId) {
        if (DBG) logdl("[shouldDefaultBeCleared: subId] " + subId);
        if (records == null) {
            if (DBG) logdl("[shouldDefaultBeCleared] return true no records subId=" + subId);
            return true;
        }
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            // If the subId parameter is INVALID_SUBSCRIPTION_ID its
            // already cleared so return false.
            if (DBG) logdl("[shouldDefaultBeCleared] return false only one subId, subId=" + subId);
            return false;
        }
        for (SubscriptionInfo record : records) {
            int id = record.getSubscriptionId();
            if (DBG) logdl("[shouldDefaultBeCleared] Record.id: " + id);
            if (id == subId) {
                logdl("[shouldDefaultBeCleared] return false subId is active, subId=" + subId);
                return false;
            }
        }
        if (DBG) logdl("[shouldDefaultBeCleared] return true not active subId=" + subId);
        return true;
    }

    // FIXME: We need we should not be assuming phoneId == slotId as it will not be true
    // when there are multiple subscriptions per sim and probably for other reasons.
    public int getSubIdUsingPhoneId(int phoneId) {
        int[] subIds = getSubId(phoneId);
        if (subIds == null || subIds.length == 0) {
            return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        }
        return subIds[0];
    }

    public int[] getSubIdUsingSlotId(int slotId) {
        return getSubId(slotId);
    }

    public List<SubscriptionInfo> getSubInfoUsingSlotIdWithCheck(int slotId, boolean needCheck) {
        if (DBG) logd("[getSubInfoUsingSlotIdWithCheck]+ slotId:" + slotId);
        enforceSubscriptionPermission();

        if (slotId == SubscriptionManager.DEFAULT_SIM_SLOT_INDEX) {
            slotId = getSlotId(getDefaultSubId());
        }
        if (!SubscriptionManager.isValidSlotId(slotId)) {
            if (DBG) logd("[getSubInfoUsingSlotIdWithCheck]- invalid slotId");
            return null;
        }

        if (needCheck && !isSubInfoReady()) {
            if (DBG) logd("[getSubInfoUsingSlotIdWithCheck]- not ready");
            return null;
        }

        Cursor cursor = mContext.getContentResolver().query(SubscriptionManager.CONTENT_URI,
                null, SubscriptionManager.SIM_SLOT_INDEX + "=?",
                new String[] {String.valueOf(slotId)}, null);
        ArrayList<SubscriptionInfo> subList = null;
        try {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    SubscriptionInfo subInfo = getSubInfoRecord(cursor);
                    if (subInfo != null)
                    {
                        if (subList == null)
                        {
                            subList = new ArrayList<SubscriptionInfo>();
                        }
                        subList.add(subInfo);
                    }
                }
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        if (DBG) logd("[getSubInfoUsingSlotId]- null info return");

        return subList;
    }

    private void validateSubId(int subId) {
        if (DBG) logd("validateSubId subId: " + subId);
        if (!SubscriptionManager.isValidSubId(subId)) {
            throw new RuntimeException("Invalid sub id passed as parameter");
        } else if (subId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID) {
            throw new RuntimeException("Default sub id passed as parameter");
        }
    }

    public void updatePhonesAvailability(PhoneProxy[] phones) {
        sProxyPhones = phones;
    }

    /**
     * @return the list of subId's that are active, is never null but the length maybe 0.
     */
    @Override
    public int[] getActiveSubIdList() {
        Set<Entry<Integer, Integer>> simInfoSet = mSlotIdxToSubId.entrySet();
        if (DBG) logdl("[getActiveSubIdList] simInfoSet=" + simInfoSet);

        int[] subIdArr = new int[simInfoSet.size()];
        int i = 0;
        for (Entry<Integer, Integer> entry: simInfoSet) {
            int sub = entry.getValue();
            subIdArr[i] = sub;
            i++;
        }

        if (DBG) logdl("[getActiveSubIdList] X subIdArr.length=" + subIdArr.length);
        return subIdArr;
    }

    /**
     * Get the SIM state for the subscriber
     * @return SIM state as the ordinal of {@See IccCardConstants.State}
     */
    @Override
    public int getSimStateForSubscriber(int subId) {
        State simState;
        String err;
        int phoneIdx = getPhoneId(subId);
        if (phoneIdx < 0) {
            simState = IccCardConstants.State.UNKNOWN;
            err = "invalid PhoneIdx";
        } else {
            Phone phone = PhoneFactory.getPhone(phoneIdx);
            if (phone == null) {
                simState = IccCardConstants.State.UNKNOWN;
                err = "phone == null";
            } else {
                IccCard icc = phone.getIccCard();
                if (icc == null) {
                    simState = IccCardConstants.State.UNKNOWN;
                    err = "icc == null";
                } else {
                    simState = icc.getState();
                    err = "";
                }
            }
        }
        if (DBG) logd("getSimStateForSubscriber: " + err + " simState=" + simState
                + " ordinal=" + simState.ordinal());
        return simState.ordinal();
    }

    private static void printStackTrace(String msg) {
        RuntimeException re = new RuntimeException();
        slogd("StackTrace - " + msg);
        StackTraceElement[] st = re.getStackTrace();
        boolean first = true;
        for (StackTraceElement ste : st) {
            if (first) {
                first = false;
            } else {
                slogd(ste.toString());
            }
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DUMP,
                "Requires DUMP");
        pw.println("SubscriptionController:");
        pw.println(" defaultSubId=" + getDefaultSubId());
        pw.println(" defaultDataSubId=" + getDefaultDataSubId());
        pw.println(" defaultVoiceSubId=" + getDefaultVoiceSubId());
        pw.println(" defaultSmsSubId=" + getDefaultSmsSubId());

        pw.println(" defaultDataPhoneId=" + SubscriptionManager
                .from(mContext).getDefaultDataPhoneId());
        pw.println(" defaultVoicePhoneId=" + SubscriptionManager.getDefaultVoicePhoneId());
        pw.println(" defaultSmsPhoneId=" + SubscriptionManager
                .from(mContext).getDefaultSmsPhoneId());
        pw.flush();

        for (Entry<Integer, Integer> entry : mSlotIdxToSubId.entrySet()) {
            pw.println(" mSlotIdToSubIdMap[" + entry.getKey() + "]: subId=" + entry.getValue());
        }
        pw.flush();
        pw.println("++++++++++++++++++++++++++++++++");

        List<SubscriptionInfo> sirl = getActiveSubscriptionInfoList();
        if (sirl != null) {
            pw.println(" ActiveSubInfoList:");
            for (SubscriptionInfo entry : sirl) {
                pw.println("  " + entry.toString());
            }
        } else {
            pw.println(" ActiveSubInfoList: is null");
        }
        pw.flush();
        pw.println("++++++++++++++++++++++++++++++++");

        sirl = getAllSubInfoList();
        if (sirl != null) {
            pw.println(" AllSubInfoList:");
            for (SubscriptionInfo entry : sirl) {
                pw.println("  " + entry.toString());
            }
        } else {
            pw.println(" AllSubInfoList: is null");
        }
        pw.flush();
        pw.println("++++++++++++++++++++++++++++++++");

        mLocalLog.dump(fd, pw, args);
        pw.flush();
        pw.println("++++++++++++++++++++++++++++++++");
        pw.flush();
    }
}
