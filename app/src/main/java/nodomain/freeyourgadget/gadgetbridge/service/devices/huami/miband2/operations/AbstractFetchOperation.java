/*  Copyright (C) 2017-2018 Andreas Shimokawa, Carsten Pfeiffer, Daniele
    Gobbetti

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.service.devices.huami.miband2.operations;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.SharedPreferences;
import android.support.annotation.CallSuper;
import android.support.annotation.NonNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.text.DateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.Logging;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.miband.MiBand2Service;
import nodomain.freeyourgadget.gadgetbridge.service.btle.BLETypeConversions;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.btle.actions.SetDeviceBusyAction;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huami.miband2.AbstractMiBand2Operation;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huami.miband2.MiBand2Support;
import nodomain.freeyourgadget.gadgetbridge.util.ArrayUtils;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

/**
 * An operation that fetches activity data. For every fetch, a new operation must
 * be created, i.e. an operation may not be reused for multiple fetches.
 */
public abstract class AbstractFetchOperation extends AbstractMiBand2Operation {
    private static final Logger LOG = LoggerFactory.getLogger(AbstractFetchOperation.class);

    protected byte lastPacketCounter;
    protected int fetchCount;
    protected BluetoothGattCharacteristic characteristicActivityData;
    protected BluetoothGattCharacteristic characteristicFetch;
    protected Calendar startTimestamp;
    protected int expectedDataLength;

    public AbstractFetchOperation(MiBand2Support support) {
        super(support);
    }

    @Override
    protected void enableNeededNotifications(TransactionBuilder builder, boolean enable) {
        if (!enable) {
            // dynamically enabled, but always disabled on finish
            builder.notify(characteristicFetch, enable);
            builder.notify(characteristicActivityData, enable);
        }
    }

    @Override
    protected void doPerform() throws IOException {
        startFetching();
    }

    protected void startFetching() throws IOException {
        lastPacketCounter = -1;

        TransactionBuilder builder = performInitialized(getName());
        getSupport().setLowLatency(builder);
        if (fetchCount == 0) {
            builder.add(new SetDeviceBusyAction(getDevice(), getContext().getString(R.string.busy_task_fetch_activity_data), getContext()));
        }
        fetchCount++;

        characteristicActivityData = getCharacteristic(MiBand2Service.UUID_CHARACTERISTIC_5_ACTIVITY_DATA);
        builder.notify(characteristicActivityData, false);

        characteristicFetch = getCharacteristic(MiBand2Service.UUID_UNKNOWN_CHARACTERISTIC4);
        builder.notify(characteristicFetch, true);

        startFetching(builder);
        builder.queue(getQueue());
    }

    protected abstract void startFetching(TransactionBuilder builder);

    protected abstract String getLastSyncTimeKey();

    @Override
    public boolean onCharacteristicChanged(BluetoothGatt gatt,
                                           BluetoothGattCharacteristic characteristic) {
        UUID characteristicUUID = characteristic.getUuid();
        if (MiBand2Service.UUID_CHARACTERISTIC_5_ACTIVITY_DATA.equals(characteristicUUID)) {
            handleActivityNotif(characteristic.getValue());
            return true;
        } else if (MiBand2Service.UUID_UNKNOWN_CHARACTERISTIC4.equals(characteristicUUID)) {
            handleActivityMetadata(characteristic.getValue());
            return true;
        } else {
            return super.onCharacteristicChanged(gatt, characteristic);
        }
    }

    @CallSuper
    protected void handleActivityFetchFinish(boolean success) {
        GB.updateTransferNotification(null,"",false,100,getContext());
        operationFinished();
        unsetBusy();
    }

    /**
     * Method to handle the incoming activity data.
     * There are two kind of messages we currently know:
     * - the first one is 11 bytes long and contains metadata (how many bytes to expect, when the data starts, etc.)
     * - the second one is 20 bytes long and contains the actual activity data
     * <p/>
     * The first message type is parsed by this method, for every other length of the value param, bufferActivityData is called.
     *
     * @param value
     */
    protected abstract void handleActivityNotif(byte[] value);

    protected abstract void bufferActivityData(byte[] value);

    protected void handleActivityMetadata(byte[] value) {
        if (value.length == 15) {
            // first two bytes are whether our request was accepted
            if (ArrayUtils.equals(value, MiBand2Service.RESPONSE_ACTIVITY_DATA_START_DATE_SUCCESS, 0)) {
                // the third byte (0x01 on success) = ?
                // the 4th - 7th bytes epresent the number of bytes/packets to expect, excluding the counter bytes
                expectedDataLength = BLETypeConversions.toUint32(Arrays.copyOfRange(value, 3, 7));

                // last 8 bytes are the start date
                Calendar startTimestamp = getSupport().fromTimeBytes(Arrays.copyOfRange(value, 7, value.length));
                setStartTimestamp(startTimestamp);

                GB.updateTransferNotification(getContext().getString(R.string.busy_task_fetch_activity_data),
                        getContext().getString(R.string.FetchActivityOperation_about_to_transfer_since,
                        DateFormat.getDateTimeInstance().format(startTimestamp.getTime())), true, 0, getContext());;
            } else {
                LOG.warn("Unexpected activity metadata: " + Logging.formatBytes(value));
                handleActivityFetchFinish(false);
            }
        } else if (value.length == 3) {
            if (Arrays.equals(MiBand2Service.RESPONSE_FINISH_SUCCESS, value)) {
                handleActivityFetchFinish(true);
            } else {
                LOG.warn("Unexpected activity metadata: " + Logging.formatBytes(value));
                handleActivityFetchFinish(false);
            }
        } else {
            LOG.warn("Unexpected activity metadata: " + Logging.formatBytes(value));
            handleActivityFetchFinish(false);
        }
    }

    protected void setStartTimestamp(Calendar startTimestamp) {
        this.startTimestamp = startTimestamp;
    }

    protected Calendar getLastStartTimestamp() {
        return startTimestamp;
    }

    protected void saveLastSyncTimestamp(@NonNull GregorianCalendar timestamp) {
        SharedPreferences.Editor editor = GBApplication.getPrefs().getPreferences().edit();
        editor.putLong(getLastSyncTimeKey(), timestamp.getTimeInMillis());
        editor.apply();
    }


    protected GregorianCalendar getLastSuccessfulSyncTime() {
        long timeStampMillis = GBApplication.getPrefs().getLong(getLastSyncTimeKey(), 0);
        if (timeStampMillis != 0) {
            GregorianCalendar calendar = BLETypeConversions.createCalendar();
            calendar.setTimeInMillis(timeStampMillis);
            return calendar;
        }
        GregorianCalendar calendar = BLETypeConversions.createCalendar();
        calendar.add(Calendar.DAY_OF_MONTH, - 100);
        return calendar;
    }
}
