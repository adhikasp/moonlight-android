package com.limelight.binding.input.driver;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.limelight.LimeLog;
import com.limelight.nvstream.input.ControllerPacket;
import com.limelight.nvstream.jni.MoonBridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedList;
import java.util.UUID;

public class SteamController extends AbstractController {

    private static final String TAG = "steamcontroller";

    private final BluetoothDriverService mManager;
    private final BluetoothDevice mDevice;
    private BluetoothGatt mGatt;
    private final Callback mCallback;
    private final Handler mHandler;
    private final LinkedList<GattOperation> mOperations;
    GattOperation mCurrentOperation = null;

    static public final UUID steamControllerService = UUID.fromString("100F6C32-1735-4313-B402-38567131E5F3");
    static public final UUID inputCharacteristic = UUID.fromString("100F6C33-1735-4313-B402-38567131E5F3");
    static public final UUID reportCharacteristic = UUID.fromString("100F6C34-1735-4313-B402-38567131E5F3");
    static private final byte[] enterValveMode = new byte[] { (byte)0xC0, (byte)0x87, 0x03, 0x08, 0x07, 0x00 };
    private boolean mIsRegistered;
    private volatile boolean mShouldReconnect = false;


    @SuppressLint("NewApi")
    private class Callback extends BluetoothGattCallback
    {
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                mHandler.post(new Runnable() {
                    @SuppressLint("MissingPermission")
                    @Override
                    public void run() {
                        mGatt.discoverServices();
                    }
                });
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (mShouldReconnect) {
                    // Intentional reconnect: close old GATT then open a fresh connection.
                    // Do NOT notify removal — the controller stays registered with the host.
                    mShouldReconnect = false;
                    gatt.close();
                    mGatt = connectGatt(false);
                } else {
                    mIsRegistered = false;
                    notifyDeviceRemoved();
                }
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == 0) {
                if (gatt.getServices().size() == 0) {
                    //Log.v(TAG, "onServicesDiscovered returned zero services; something has gone horribly wrong down in Android's Bluetooth stack.");
                    gatt.disconnect();
                    mGatt = connectGatt(false);
                }
                else {
                    probeService();
                }
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            //Log.v(TAG, "onCharacteristicRead status=" + status + " uuid=" + characteristic.getUuid());

            if (characteristic.getUuid().equals(reportCharacteristic)) {
                // TODO: Report features
            }

            finishCurrentGattOperation();
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            //Log.v(TAG, "onCharacteristicWrite status=" + status + " uuid=" + characteristic.getUuid());

            if (characteristic.getUuid().equals(reportCharacteristic)) {
                // Only register controller with the native side once it has been fully configured
                if (!isRegistered()) {
                    LimeLog.info("Registering Steam Controller with ID: " + getIdentifier());
                    setRegistered();
                    notifyDeviceAdded();
                }
            }

            finishCurrentGattOperation();
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            // Called on Android < API 33
            if (characteristic.getUuid().equals(inputCharacteristic)) {
                handleRead(ByteBuffer.wrap(characteristic.getValue()));
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) {
            // Called on Android API 33+ — value param is reliable, do NOT call characteristic.getValue()
            if (characteristic.getUuid().equals(inputCharacteristic)) {
                handleRead(ByteBuffer.wrap(value));
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            BluetoothGattCharacteristic chr = descriptor.getCharacteristic();
            //Log.v(TAG, "onDescriptorWrite status=" + status + " uuid=" + chr.getUuid() + " descriptor=" + descriptor.getUuid());

            if (chr.getUuid().equals(inputCharacteristic)) {
                BluetoothGattCharacteristic reportChr = chr.getService().getCharacteristic(reportCharacteristic);
                if (reportChr != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeCharacteristic(reportChr, enterValveMode, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                    } else {
                        reportChr.setValue(enterValveMode);
                        gatt.writeCharacteristic(reportChr);
                    }
                }
            }

            finishCurrentGattOperation();
        }
    }
    
    
    private boolean isRegistered() {
        return mIsRegistered;
    }
    private void setRegistered() {
        mIsRegistered = true;
    }

    public SteamController(UsbDriverListener listener, BluetoothDriverService manager, BluetoothDevice device)
    {
        super(0x1234, listener, 0x28de, 0x1102);
        mManager = manager;
        mDevice = device;
        mCallback = new Callback();
        mHandler = new Handler(Looper.getMainLooper());
        mOperations = new LinkedList<>();

        this.type = MoonBridge.LI_CTYPE_UNKNOWN;
        this.capabilities = (short)(MoonBridge.LI_CCAP_ANALOG_TRIGGERS | MoonBridge.LI_CCAP_RUMBLE);
        this.supportedButtonFlags =
            ControllerPacket.A_FLAG | ControllerPacket.B_FLAG |
            ControllerPacket.X_FLAG | ControllerPacket.Y_FLAG |
            ControllerPacket.UP_FLAG | ControllerPacket.DOWN_FLAG |
            ControllerPacket.LEFT_FLAG | ControllerPacket.RIGHT_FLAG |
            ControllerPacket.LB_FLAG | ControllerPacket.RB_FLAG |
            ControllerPacket.LS_CLK_FLAG | ControllerPacket.RS_CLK_FLAG |
            ControllerPacket.PLAY_FLAG | ControllerPacket.BACK_FLAG |
            ControllerPacket.SPECIAL_BUTTON_FLAG |
            ControllerPacket.PADDLE1_FLAG | ControllerPacket.PADDLE2_FLAG;

        mGatt = connectGatt(false);
    }

    @SuppressLint("NewApi")
    private void probeService() {
        for (BluetoothGattService service : mGatt.getServices()) {
            if (service.getUuid().equals(steamControllerService)) {
                LimeLog.info("Found Valve steam controller service " + service.getUuid());

                for (BluetoothGattCharacteristic chr : service.getCharacteristics()) {
                    if (chr.getUuid().equals(inputCharacteristic)) {
                        LimeLog.info("Found input characteristic");
                        // Start notifications
                        BluetoothGattDescriptor cccd = chr.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
                        if (cccd != null) {
                            enableNotification(chr.getUuid());
                        }
                    }
                }
                return;
            }
        }
    }

    @SuppressLint({"MissingPermission", "NewApi"})
    private BluetoothGatt connectGatt(boolean managed) {
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                return mDevice.connectGatt(mManager, managed, mCallback, BluetoothDevice.TRANSPORT_LE);
            } catch (Exception e) {
                return mDevice.connectGatt(mManager, managed, mCallback);
            }
        } else {
            return mDevice.connectGatt(mManager, managed, mCallback);
        }
    }

    public String getIdentifier() {
        return String.format("SteamController.%s", mDevice.getAddress());
    }

    private static final int BLEButtonChunk1 = 0x10;
    private static final int BLEButtonChunk2 = 0x20;
    private static final int BLEButtonChunk3 = 0x40;
    private static final int BLELeftJoystickChunk = 0x80;
    private static final int BLELeftTrackpadChunk = 0x100;
    private static final int BLERightTrackpadChunk = 0x200;

    protected void handleRead(ByteBuffer buffer) {
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.get(); // skip first byte

        int type = Byte.toUnsignedInt(buffer.get()) | Byte.toUnsignedInt(buffer.get()) << 8;
        if((type & BLEButtonChunk1) != 0)
        {
            byte[] buttons = new byte[3];
            buffer.get(buttons);

            long b = Byte.toUnsignedLong(buttons[0]) | Byte.toUnsignedLong(buttons[1]) << 8 | Byte.toUnsignedLong(buttons[2]) << 16;
            setButtonFlag(ControllerPacket.RS_CLK_FLAG, (int) (b & 0x00000001));
            setButtonFlag(ControllerPacket.LS_CLK_FLAG, (int) (b & 0x00000002));

            setButtonFlag(ControllerPacket.RB_FLAG, (int) (b & 0x00000004));
            setButtonFlag(ControllerPacket.LB_FLAG, (int) (b & 0x00000008));

            setButtonFlag(ControllerPacket.Y_FLAG, (int) (b & 0x00000010));
            setButtonFlag(ControllerPacket.B_FLAG, (int) (b & 0x00000020));
            setButtonFlag(ControllerPacket.X_FLAG, (int) (b & 0x00000040));
            setButtonFlag(ControllerPacket.A_FLAG, (int) (b & 0x00000080));

            setButtonFlag(ControllerPacket.UP_FLAG, (int) (b & 0x00000100));
            setButtonFlag(ControllerPacket.RIGHT_FLAG, (int) (b & 0x00000200));
            setButtonFlag(ControllerPacket.LEFT_FLAG, (int) (b & 0x00000400));
            setButtonFlag(ControllerPacket.DOWN_FLAG, (int) (b & 0x00000800));

            setButtonFlag(ControllerPacket.BACK_FLAG, (int) (b & 0x00001000));
            setButtonFlag(ControllerPacket.SPECIAL_BUTTON_FLAG, (int) (b & 0x00002000));
            setButtonFlag(ControllerPacket.PLAY_FLAG, (int) (b & 0x00004000));

            Log.v(TAG, "Buttons: "+Long.toBinaryString(b));
        }
        if((type & BLEButtonChunk2) != 0)
        {
            int left = Byte.toUnsignedInt(buffer.get());
            int right = Byte.toUnsignedInt(buffer.get());
            Log.v(TAG, "Triggers: "+left+" | "+right);
            leftTrigger = left/255.0f;
            rightTrigger = right/255.0f;
        }
        if((type & BLEButtonChunk3) != 0)
        {
            byte[] buttons = new byte[3];
            buffer.get(buttons);
            long b = Byte.toUnsignedLong(buttons[0]) | Byte.toUnsignedLong(buttons[1]) << 8 | Byte.toUnsignedLong(buttons[2]) << 16;
            setButtonFlag(ControllerPacket.PADDLE1_FLAG, (int)(b & 0x00000001)); // left grip
            setButtonFlag(ControllerPacket.PADDLE2_FLAG, (int)(b & 0x00000002)); // right grip
        }
        if((type & BLELeftJoystickChunk) != 0)
        {
            int x = buffer.getShort();
            int y = ~buffer.getShort();
            Log.v(TAG, "Joystick: "+x+" | "+y);
            leftStickX = x / (float)Short.MAX_VALUE;
            leftStickY = y / (float)Short.MAX_VALUE;
        }
        if((type & BLELeftTrackpadChunk) != 0)
        {
            buffer.getShort();
            buffer.getShort();
        }
        if((type & BLERightTrackpadChunk) != 0)
        {
            int x = buffer.getShort();
            int y = ~buffer.getShort();
            Log.v(TAG, "Right Pad: "+x+" | "+y);
            rightStickX = x / (float)Short.MAX_VALUE;
            rightStickY = y / (float)Short.MAX_VALUE;
        }

        reportInput();

    }

    @Override
    public boolean start() {
        return false;
    }

    @SuppressLint("MissingPermission")
    @Override
    public void stop() {
        mShouldReconnect = false;
        if (mGatt != null) {
            mGatt.disconnect();
            mGatt.close();
            mGatt = null;
        }
        mIsRegistered = false;
    }

    @Override
    public void rumble(short lowFreqMotor, short highFreqMotor) {
        if (!isRegistered()) return;
        byte[] hapticCmd = new byte[] {
            (byte)0x8f, (byte)0x07,
            (byte)(lowFreqMotor & 0xFF),  (byte)((lowFreqMotor >> 8) & 0xFF),   // left pad amplitude
            (byte)(highFreqMotor & 0xFF), (byte)((highFreqMotor >> 8) & 0xFF),  // right pad amplitude
            (byte)0xe8, (byte)0x03  // period ~1000µs
        };
        writeCharacteristic(reportCharacteristic, hapticCmd);
    }

    @Override
    public void rumbleTriggers(short leftTrigger, short rightTrigger) {
        // Steam Controller has no trigger haptics; delegate to main rumble
        rumble(leftTrigger, rightTrigger);
    }

    @SuppressLint("MissingPermission")
    public void reconnect() {
        mIsRegistered = false;
        mShouldReconnect = true;
        if (mGatt != null) {
            mGatt.disconnect(); // triggers onConnectionStateChange(STATE_DISCONNECTED)
        } else {
            mShouldReconnect = false;
            mGatt = connectGatt(false);
        }
    }

    static class GattOperation {
        private enum Operation {
            CHR_READ,
            CHR_WRITE,
            ENABLE_NOTIFICATION
        }

        Operation mOp;
        UUID mUuid;
        byte[] mValue;
        BluetoothGatt mGatt;
        boolean mResult = true;

        private GattOperation(BluetoothGatt gatt, GattOperation.Operation operation, UUID uuid) {
            mGatt = gatt;
            mOp = operation;
            mUuid = uuid;
        }

        private GattOperation(BluetoothGatt gatt, GattOperation.Operation operation, UUID uuid, byte[] value) {
            mGatt = gatt;
            mOp = operation;
            mUuid = uuid;
            mValue = value;
        }

        @SuppressLint({"NewApi", "MissingPermission"})
        public void run() {
            // This is executed in main thread
            BluetoothGattCharacteristic chr;

            switch (mOp) {
                case CHR_READ:
                    chr = getCharacteristic(mUuid);
                    //Log.v(TAG, "Reading characteristic " + chr.getUuid());
                    if (!mGatt.readCharacteristic(chr)) {
                        LimeLog.warning("Unable to read characteristic " + mUuid.toString());
                        mResult = false;
                        break;
                    }
                    mResult = true;
                    break;
                case CHR_WRITE:
                    chr = getCharacteristic(mUuid);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        int writeResult = mGatt.writeCharacteristic(chr, mValue, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                        if (writeResult != BluetoothGatt.GATT_SUCCESS) {
                            LimeLog.warning("Unable to write characteristic " + mUuid.toString());
                            mResult = false;
                            break;
                        }
                    } else {
                        chr.setValue(mValue);
                        if (!mGatt.writeCharacteristic(chr)) {
                            LimeLog.warning("Unable to write characteristic " + mUuid.toString());
                            mResult = false;
                            break;
                        }
                    }
                    mResult = true;
                    break;
                case ENABLE_NOTIFICATION:
                    chr = getCharacteristic(mUuid);
                    //Log.v(TAG, "Writing descriptor of " + chr.getUuid());
                    if (chr != null) {
                        BluetoothGattDescriptor cccd = chr.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
                        if (cccd != null) {
                            int properties = chr.getProperties();
                            byte[] value;
                            if ((properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == BluetoothGattCharacteristic.PROPERTY_NOTIFY) {
                                value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
                            } else if ((properties & BluetoothGattCharacteristic.PROPERTY_INDICATE) == BluetoothGattCharacteristic.PROPERTY_INDICATE) {
                                value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
                            } else {
                                LimeLog.warning("Unable to start notifications on input characteristic");
                                mResult = false;
                                return;
                            }

                            mGatt.setCharacteristicNotification(chr, true);
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                int writeResult = mGatt.writeDescriptor(cccd, value);
                                if (writeResult != BluetoothGatt.GATT_SUCCESS) {
                                    LimeLog.warning("Unable to write descriptor " + mUuid.toString());
                                    mResult = false;
                                    return;
                                }
                            } else {
                                cccd.setValue(value);
                                if (!mGatt.writeDescriptor(cccd)) {
                                    LimeLog.warning("Unable to write descriptor " + mUuid.toString());
                                    mResult = false;
                                    return;
                                }
                            }
                            mResult = true;
                        }
                    }
            }
        }

        public boolean finish() {
            return mResult;
        }

        @SuppressLint("NewApi")
        private BluetoothGattCharacteristic getCharacteristic(UUID uuid) {
            BluetoothGattService valveService = mGatt.getService(steamControllerService);
            if (valveService == null)
                return null;
            return valveService.getCharacteristic(uuid);
        }

        static public GattOperation readCharacteristic(BluetoothGatt gatt, UUID uuid) {
            return new GattOperation(gatt, Operation.CHR_READ, uuid);
        }

        static public GattOperation writeCharacteristic(BluetoothGatt gatt, UUID uuid, byte[] value) {
            return new GattOperation(gatt, Operation.CHR_WRITE, uuid, value);
        }

        static public GattOperation enableNotification(BluetoothGatt gatt, UUID uuid) {
            return new GattOperation(gatt, Operation.ENABLE_NOTIFICATION, uuid);
        }
    }
    private void finishCurrentGattOperation() {
        GattOperation op = null;
        synchronized (mOperations) {
            if (mCurrentOperation != null) {
                op = mCurrentOperation;
                mCurrentOperation = null;
            }
        }
        if (op != null) {
            boolean result = op.finish(); // TODO: Maybe in main thread as well?

            // Our operation failed, let's add it back to the beginning of our queue.
            if (!result) {
                mOperations.addFirst(op);
            }
        }
        executeNextGattOperation();
    }
    private void executeNextGattOperation() {
        synchronized (mOperations) {
            if (mCurrentOperation != null)
                return;

            if (mOperations.isEmpty())
                return;

            mCurrentOperation = mOperations.removeFirst();
        }

        // Run in main thread
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                synchronized (mOperations) {
                    if (mCurrentOperation == null) {
                        LimeLog.warning("Current operation null in executor?");
                        return;
                    }

                    mCurrentOperation.run();
                    // now wait for the GATT callback and when it comes, finish this operation
                }
            }
        });
    }
    private void queueGattOperation(GattOperation op) {
        synchronized (mOperations) {
            mOperations.add(op);
        }
        executeNextGattOperation();
    }
    private void enableNotification(UUID chrUuid) {
        GattOperation op = GattOperation.enableNotification(mGatt, chrUuid);
        queueGattOperation(op);
    }
    public void writeCharacteristic(UUID uuid, byte[] value) {
        GattOperation op = GattOperation.writeCharacteristic(mGatt, uuid, value);
        queueGattOperation(op);
    }
    public void readCharacteristic(UUID uuid) {
        GattOperation op = GattOperation.readCharacteristic(mGatt, uuid);
        queueGattOperation(op);
    }
}
