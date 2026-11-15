import type { PermissionState, PluginListenerHandle } from '@capacitor/core';

/**
 * Identifies a device. On Android it is the device's MAC address (`00:11:22:33:44:55`); on iOS it is the
 * `connectionID` iOS gives a connected External Accessory, a number.
 */
export type DeviceId = string | number;

/**
 * A device as `list`, `discoverUnpaired` and the `deviceDiscovered` event report it.
 *
 * Android fills `id`, `address`, `name` (absent when the device has not told its name) and `class` (the Bluetooth device
 * class as a number). iOS fills every field: `id` and `address` are both the accessory's `connectionID`, `class` is an
 * empty string, and `protocols` lists the External Accessory protocol strings the accessory speaks.
 */
export interface BluetoothClassicDevice {
  id: DeviceId;
  address: DeviceId;
  name?: string;
  class?: number | string;
  manufacturer?: string;
  modelNumber?: string;
  serialNumber?: string;
  firmwareRevision?: string;
  hardwareRevision?: string;
  protocols?: string[];
}

export interface DevicesResult {
  devices: BluetoothClassicDevice[];
}

export interface ConnectOptions {
  deviceId: DeviceId;
  /**
   * What to open on the device. Android: RFCOMM service UUIDs, of which the first is used (the serial port profile is
   * `00001101-0000-1000-8000-00805F9B34FB`). iOS: External Accessory protocol strings, every one of which is opened; the
   * accessory has to speak all of them.
   */
  interfaces: string[];
  /**
   * Android only: open an insecure RFCOMM socket (no authentication and no encryption). Ignored on iOS.
   *
   * @default false
   */
  insecure?: boolean;
}

/**
 * One open interface of a device. Android ignores `interfaceId` when it looks the connection up (a device has one
 * connection there) and echoes it back on the events; iOS keeps a session per protocol string and needs it.
 */
export interface InterfaceOptions {
  deviceId: DeviceId;
  interfaceId: string;
}

export interface DelimiterOptions extends InterfaceOptions {
  /** The text that ends a message, `\n` for a reader that sends a line per tag. It is included in what is returned. */
  delimiter: string;
}

export interface WriteOptions extends InterfaceOptions {
  /** The bytes to send, base64 encoded (see `bytesToBase64` and `textToBase64`). */
  data: string;
}

export interface EnabledResult {
  enabled: boolean;
}

export interface ConnectedResult {
  connected: boolean;
}

export interface AvailableResult {
  /** What is waiting in the receive buffer: characters on Android, bytes on iOS. */
  available: number;
}

export interface ReadResult {
  /** The text read, decoded as UTF-8. Empty when there was nothing to read. */
  data: string;
}

export interface BluetoothState {
  /** The app's Bluetooth authorization: `notDetermined`, `restricted`, `denied`, `allowedAlways` or `unknown`. */
  authorization: string;
  /** The External Accessory protocols the app declares in `UISupportedExternalAccessoryProtocols`. */
  supportedProtocols: string[];
  /** How many accessories iOS reports connected, whichever app they belong to. */
  connectedAccessories: number;
  /** The iOS version. */
  systemVersion: string;
}

export interface PermissionStatus {
  /** Android 12 and later: Nearby devices (`BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT`). iOS: the Bluetooth authorization. */
  bluetooth: PermissionState;
  /** Android 11 and earlier, where finding devices needs location. Always `granted` where it is not needed. */
  location: PermissionState;
}

/** A delimited message, delivered after `subscribe`. */
export interface DataEvent {
  deviceId: DeviceId;
  interfaceId: string;
  /** The message including its delimiter, decoded as UTF-8. */
  data: string;
}

/** Bytes as they were read from the device, delivered after `subscribeRaw`. */
export interface RawDataEvent {
  deviceId: DeviceId;
  interfaceId: string;
  /** The bytes, base64 encoded. */
  data: string;
}

/** A connection that `connect` opened has ended, because it was lost or because `disconnect` closed it. */
export interface DisconnectedEvent {
  deviceId: DeviceId;
  /** The interface that ended: the RFCOMM UUID on Android, the protocol string on iOS. */
  interfaceId: string;
  /** `Device connection was lost` or `Device was manually disconnected`. */
  message: string;
}

/**
 * The codes a call rejects with, besides Capacitor's own `UNIMPLEMENTED` (a method the platform does not have) and
 * `UNAVAILABLE` (the web).
 */
export type BluetoothClassicErrorCode =
  | 'INVALID_ARGUMENT'
  | 'BLUETOOTH_UNAVAILABLE'
  | 'BLUETOOTH_DISABLED'
  | 'PERMISSION_DENIED'
  | 'NO_CONNECTION'
  | 'NOT_OPEN'
  | 'CONNECT_FAILED'
  | 'WRITE_FAILED'
  | 'DISCOVERY_FAILED'
  | 'PICKER_CANCELLED'
  | 'PICKER_NOT_FOUND'
  | 'PICKER_FAILED';

export interface BluetoothClassicPlugin {
  /**
   * The devices to connect to. Android: the bonded (paired) devices. iOS: the accessories connected to the phone.
   *
   * On Android the permissions this needs (see `requestPermissions`) are asked for first when they are missing.
   */
  list(): Promise<DevicesResult>;

  /**
   * Finds devices. Android: a Bluetooth discovery of about twelve seconds, which emits `deviceDiscovered` for each
   * device it sees and resolves with all of them. iOS: shows the system accessory picker and resolves with the
   * accessories connected afterwards; a newly connected accessory is also announced by `deviceDiscovered`.
   */
  discoverUnpaired(): Promise<DevicesResult>;

  /**
   * Opens a connection and resolves once it is open. Its end is reported by the `disconnected` event. Connecting to a
   * device that already has a connection replaces it on Android; on iOS a protocol whose session is already open keeps
   * it.
   */
  connect(options: ConnectOptions): Promise<void>;

  /** Closes the connection. A `disconnected` event follows for each interface that was open. */
  disconnect(options: InterfaceOptions): Promise<void>;

  /** Closes every connection. */
  disconnectAll(): Promise<void>;

  /** Whether the connection is up. */
  isConnected(options: InterfaceOptions): Promise<ConnectedResult>;

  /** Whether Bluetooth is switched on. */
  isEnabled(): Promise<EnabledResult>;

  /** Android only: asks the user to switch Bluetooth on, and rejects when they decline. */
  enable(): Promise<void>;

  /** Android only: opens the Bluetooth settings. */
  showBluetoothSettings(): Promise<void>;

  /** Starts delivering delimited messages as `data` events. A second call replaces the delimiter. */
  subscribe(options: DelimiterOptions): Promise<void>;

  /** Stops the `data` events. */
  unsubscribe(options: InterfaceOptions): Promise<void>;

  /** Starts delivering the bytes as they are read, as `rawData` events. */
  subscribeRaw(options: InterfaceOptions): Promise<void>;

  /** Stops the `rawData` events. */
  unsubscribeRaw(options: InterfaceOptions): Promise<void>;

  /** Sends bytes. */
  write(options: WriteOptions): Promise<void>;

  /** How much is waiting in the receive buffer. */
  available(options: InterfaceOptions): Promise<AvailableResult>;

  /** Takes everything waiting in the receive buffer. */
  read(options: InterfaceOptions): Promise<ReadResult>;

  /** Takes the receive buffer up to and including the delimiter, or nothing when the delimiter has not arrived. */
  readUntil(options: DelimiterOptions): Promise<ReadResult>;

  /** Empties the receive buffer. */
  clear(options: InterfaceOptions): Promise<void>;

  /**
   * iOS only: the Bluetooth authorization, the declared accessory protocols and the connected accessories, without
   * asking for anything.
   */
  getBluetoothState(): Promise<BluetoothState>;

  /** What the app may do now, without asking. */
  checkPermissions(): Promise<PermissionStatus>;

  /**
   * Asks for what finding and connecting devices needs. Android 12 and later: Nearby devices. Android 10 and 11: precise
   * location, and background location too when the app sets `accessBackgroundLocation` (see the README). Android 9 and
   * earlier: approximate location. iOS: the Bluetooth permission, which `isEnabled` needs.
   */
  requestPermissions(): Promise<PermissionStatus>;

  addListener(eventName: 'data', listenerFunc: (event: DataEvent) => void): Promise<PluginListenerHandle>;

  addListener(eventName: 'rawData', listenerFunc: (event: RawDataEvent) => void): Promise<PluginListenerHandle>;

  addListener(
    eventName: 'disconnected',
    listenerFunc: (event: DisconnectedEvent) => void,
  ): Promise<PluginListenerHandle>;

  addListener(
    eventName: 'deviceDiscovered',
    listenerFunc: (device: BluetoothClassicDevice) => void,
  ): Promise<PluginListenerHandle>;

  /** Removes every listener of this plugin. */
  removeAllListeners(): Promise<void>;
}
