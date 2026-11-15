# capacitor-bluetooth-classic

Bluetooth Classic serial connections for Capacitor 8 apps: RFCOMM (the serial port profile) on Android, and the External
Accessory framework (MFi accessories) on iOS, in one plugin.

It is written for hardware that streams text over a serial link: RFID readers, scales and weigh heads that send a line per
reading and take short commands. It is a port of `cordova-plugin-bluetooth-classic-serial-port` to Capacitor, with the
same methods and the same data semantics; callbacks that fired repeatedly are events now.

## Install

```bash
npm install capacitor-bluetooth-classic
npx cap sync
```

Requires Capacitor 8. Android 8.0 (API 26) or newer, iOS 15 or newer. On iOS the plugin is a Swift package; CocoaPods
works too. There is no web implementation: a browser has no Bluetooth Classic, so every call rejects with `UNAVAILABLE`.

### iOS: declare the accessory protocols

iOS lets an app talk only to the accessories whose protocol strings its `Info.plist` lists under
`UISupportedExternalAccessoryProtocols`. The plugin cannot add them for you; put the protocols of the accessories you
support in the app:

```xml
<key>UISupportedExternalAccessoryProtocols</key>
<array>
  <string>com.example.reader</string>
</array>
```

An app that ships with this key also has to be approved for the accessories through Apple's MFi programme. `isEnabled` and
`requestPermissions` use Core Bluetooth, which needs `NSBluetoothAlwaysUsageDescription` in the `Info.plist`; nothing else
in the plugin raises a prompt, and the Bluetooth permission is asked for only when one of those two is called.

### Android: permissions

The plugin's manifest declares what it needs, and `list` and `discoverUnpaired` ask for what is missing before they run, as
the Cordova plugin did:

- Android 12 (API 31) and later, in an app that targets it: Nearby devices (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`).
- Android 10 and 11: precise location. Background location as well only when the app sets `accessBackgroundLocation`
  (default `false`), and the app's own manifest then has to declare `ACCESS_BACKGROUND_LOCATION`:

  ```json
  { "plugins": { "BluetoothClassic": { "accessBackgroundLocation": true } } }
  ```

- Android 9 and earlier: approximate location.

`connect` does not ask: on Android 12 and later it needs `BLUETOOTH_CONNECT` granted already (call `requestPermissions`),
and its connect fails with `Bluetooth Connect permission not granted.` otherwise.

## Use

```typescript
import { BluetoothClassic, textToBase64 } from 'capacitor-bluetooth-classic';

const SPP = '00001101-0000-1000-8000-00805F9B34FB';
const { devices } = await BluetoothClassic.list();
const reader = devices[0];
// Android: the device address and the serial port profile. iOS: the accessory's connectionID and its protocol strings.
const interfaces = reader.protocols ?? [SPP];
const interfaceId = reader.protocols?.[0] ?? String(reader.address);

await BluetoothClassic.addListener('data', (event) => console.log('line', event.data));
await BluetoothClassic.addListener('disconnected', (event) => console.log('gone', event.message));

await BluetoothClassic.connect({ deviceId: reader.address, interfaces });
await BluetoothClassic.subscribe({ deviceId: reader.address, interfaceId, delimiter: '\n' });
await BluetoothClassic.write({ deviceId: reader.address, interfaceId, data: textToBase64('{RW}\r\n') });
```

### Data: text up to a delimiter, or raw bytes

What a connection receives goes into a receive buffer. `subscribe` takes a delimiter and delivers every complete message,
delimiter included, as a `data` event; whatever follows the last delimiter waits for the rest. A read that brought several
messages at once delivers each one as its own event, in order. `subscribeRaw` delivers the bytes of each read as they came,
base64 encoded, as a `rawData` event, and does not take them out of the buffer. Without a subscription the buffer is read
with `read`, `readUntil` and `available`, and emptied with `clear`.

A subscription starts with the next read: text already in the buffer is delivered once more arrives. Text is decoded as
UTF-8. On Android each read is decoded on its own, as before; on iOS the buffer holds bytes and the message is cut at the
delimiter's bytes.

### Connections and their end

`connect` resolves once the connection is open. Its end arrives as a `disconnected` event: `Device connection was lost`
when the device went away, `Device was manually disconnected` after `disconnect` or `disconnectAll`. A connect that fails
rejects and is followed by no event.

On Android a device has one connection, addressed by its MAC address; `interfaceId` in a call is echoed back on the events.
A second `connect` to the same device replaces the first, which ends without an event. On iOS an accessory has a session
per protocol string, keyed by its `connectionID` and the protocol, so that two accessories of one model each have their
own; a `connect` for a protocol whose session is open keeps it.

Connections outlive the page: a reloaded page finds them with `isConnected`, and on Android a `connect` takes them over.

### Finding devices

On Android `list` returns the bonded devices and `discoverUnpaired` runs a discovery, emitting `deviceDiscovered` for
each device it sees. On iOS `list` returns the connected accessories and `discoverUnpaired` shows the system accessory
picker; an accessory that connects (picked there, or paired and coming into range) is announced by `deviceDiscovered`.
The picker presents from the application delegate's `window`; in an app on the scene lifecycle that property is nil and
the picker would show nothing, so the plugin gives the delegate the key window first.

### Errors

A rejected call carries a `code` (`BluetoothClassicErrorCode`), and the message the Cordova plugin used where it had one.
A method a platform does not have rejects with `UNIMPLEMENTED`: `enable` and `showBluetoothSettings` on iOS,
`getBluetoothState` on Android.

### Differences from the Cordova plugin

- Calls take one options object and return promises; repeated callbacks are the `data`, `rawData`, `disconnected` and
  `deviceDiscovered` events. `setDeviceDiscoveredListener` and `clearDeviceDiscoveredListener` are gone: add and remove a
  `deviceDiscovered` listener instead. `connectInsecure` is `connect` with `insecure: true`.
- Bytes cross the bridge base64 encoded: `write` takes `data` as base64 and `rawData` delivers it so. `bytesToBase64`,
  `base64ToBytes`, `textToBase64` and `base64ToText` do the conversions.
- `isEnabled` and `isConnected` resolve `{ enabled }` and `{ connected }` instead of rejecting for false; `list` and
  `discoverUnpaired` resolve `{ devices }`.
- Android connects with the first of `interfaces` (the Cordova plugin effectively used the last); a discovery that cannot
  start rejects instead of never answering.
- iOS creates its Core Bluetooth manager on the first `isEnabled` or `requestPermissions` rather than at start, and
  `isEnabled` waits briefly for its first state instead of answering `false`. An accessory is announced once, not once
  per protocol, and `available` exists on iOS too.

## Development

- `npm test` runs the TypeScript unit tests, `npm run test:parity` checks that both platforms implement exactly the API.
- `npm run test:android` runs the JVM tests: the connection registry against fake links, the receive buffer and the
  permission policy.
- `npm run test:ios:core` runs the receive buffer tests on a Mac (`swift test`); `npm run test:ios` runs them and the
  plugin tests on an iOS simulator.
- `example-app/` is a test page for a phone: it lists and finds devices, connects, subscribes, writes and shows what
  arrives.

## API

<docgen-index>

* [`list()`](#list)
* [`discoverUnpaired()`](#discoverunpaired)
* [`connect(...)`](#connect)
* [`disconnect(...)`](#disconnect)
* [`disconnectAll()`](#disconnectall)
* [`isConnected(...)`](#isconnected)
* [`isEnabled()`](#isenabled)
* [`enable()`](#enable)
* [`showBluetoothSettings()`](#showbluetoothsettings)
* [`subscribe(...)`](#subscribe)
* [`unsubscribe(...)`](#unsubscribe)
* [`subscribeRaw(...)`](#subscriberaw)
* [`unsubscribeRaw(...)`](#unsubscriberaw)
* [`write(...)`](#write)
* [`available(...)`](#available)
* [`read(...)`](#read)
* [`readUntil(...)`](#readuntil)
* [`clear(...)`](#clear)
* [`getBluetoothState()`](#getbluetoothstate)
* [`checkPermissions()`](#checkpermissions)
* [`requestPermissions()`](#requestpermissions)
* [`addListener('data', ...)`](#addlistenerdata-)
* [`addListener('rawData', ...)`](#addlistenerrawdata-)
* [`addListener('disconnected', ...)`](#addlistenerdisconnected-)
* [`addListener('deviceDiscovered', ...)`](#addlistenerdevicediscovered-)
* [`removeAllListeners()`](#removealllisteners)
* [Interfaces](#interfaces)
* [Type Aliases](#type-aliases)

</docgen-index>

<docgen-api>
<!--Update the source file JSDoc comments and rerun docgen to update the docs below-->

### list()

```typescript
list() => Promise<DevicesResult>
```

The devices to connect to. Android: the bonded (paired) devices. iOS: the accessories connected to the phone.

On Android the permissions this needs (see `requestPermissions`) are asked for first when they are missing.

**Returns:** <code>Promise&lt;<a href="#devicesresult">DevicesResult</a>&gt;</code>

--------------------


### discoverUnpaired()

```typescript
discoverUnpaired() => Promise<DevicesResult>
```

Finds devices. Android: a Bluetooth discovery of about twelve seconds, which emits `deviceDiscovered` for each
device it sees and resolves with all of them. iOS: shows the system accessory picker and resolves with the
accessories connected afterwards; a newly connected accessory is also announced by `deviceDiscovered`.

**Returns:** <code>Promise&lt;<a href="#devicesresult">DevicesResult</a>&gt;</code>

--------------------


### connect(...)

```typescript
connect(options: ConnectOptions) => Promise<void>
```

Opens a connection and resolves once it is open. Its end is reported by the `disconnected` event. Connecting to a
device that already has a connection replaces it on Android; on iOS a protocol whose session is already open keeps
it.

| Param         | Type                                                      |
| ------------- | --------------------------------------------------------- |
| **`options`** | <code><a href="#connectoptions">ConnectOptions</a></code> |

--------------------


### disconnect(...)

```typescript
disconnect(options: InterfaceOptions) => Promise<void>
```

Closes the connection. A `disconnected` event follows for each interface that was open.

| Param         | Type                                                          |
| ------------- | ------------------------------------------------------------- |
| **`options`** | <code><a href="#interfaceoptions">InterfaceOptions</a></code> |

--------------------


### disconnectAll()

```typescript
disconnectAll() => Promise<void>
```

Closes every connection.

--------------------


### isConnected(...)

```typescript
isConnected(options: InterfaceOptions) => Promise<ConnectedResult>
```

Whether the connection is up.

| Param         | Type                                                          |
| ------------- | ------------------------------------------------------------- |
| **`options`** | <code><a href="#interfaceoptions">InterfaceOptions</a></code> |

**Returns:** <code>Promise&lt;<a href="#connectedresult">ConnectedResult</a>&gt;</code>

--------------------


### isEnabled()

```typescript
isEnabled() => Promise<EnabledResult>
```

Whether Bluetooth is switched on.

**Returns:** <code>Promise&lt;<a href="#enabledresult">EnabledResult</a>&gt;</code>

--------------------


### enable()

```typescript
enable() => Promise<void>
```

Android only: asks the user to switch Bluetooth on, and rejects when they decline.

--------------------


### showBluetoothSettings()

```typescript
showBluetoothSettings() => Promise<void>
```

Android only: opens the Bluetooth settings.

--------------------


### subscribe(...)

```typescript
subscribe(options: DelimiterOptions) => Promise<void>
```

Starts delivering delimited messages as `data` events. A second call replaces the delimiter.

| Param         | Type                                                          |
| ------------- | ------------------------------------------------------------- |
| **`options`** | <code><a href="#delimiteroptions">DelimiterOptions</a></code> |

--------------------


### unsubscribe(...)

```typescript
unsubscribe(options: InterfaceOptions) => Promise<void>
```

Stops the `data` events.

| Param         | Type                                                          |
| ------------- | ------------------------------------------------------------- |
| **`options`** | <code><a href="#interfaceoptions">InterfaceOptions</a></code> |

--------------------


### subscribeRaw(...)

```typescript
subscribeRaw(options: InterfaceOptions) => Promise<void>
```

Starts delivering the bytes as they are read, as `rawData` events.

| Param         | Type                                                          |
| ------------- | ------------------------------------------------------------- |
| **`options`** | <code><a href="#interfaceoptions">InterfaceOptions</a></code> |

--------------------


### unsubscribeRaw(...)

```typescript
unsubscribeRaw(options: InterfaceOptions) => Promise<void>
```

Stops the `rawData` events.

| Param         | Type                                                          |
| ------------- | ------------------------------------------------------------- |
| **`options`** | <code><a href="#interfaceoptions">InterfaceOptions</a></code> |

--------------------


### write(...)

```typescript
write(options: WriteOptions) => Promise<void>
```

Sends bytes.

| Param         | Type                                                  |
| ------------- | ----------------------------------------------------- |
| **`options`** | <code><a href="#writeoptions">WriteOptions</a></code> |

--------------------


### available(...)

```typescript
available(options: InterfaceOptions) => Promise<AvailableResult>
```

How much is waiting in the receive buffer.

| Param         | Type                                                          |
| ------------- | ------------------------------------------------------------- |
| **`options`** | <code><a href="#interfaceoptions">InterfaceOptions</a></code> |

**Returns:** <code>Promise&lt;<a href="#availableresult">AvailableResult</a>&gt;</code>

--------------------


### read(...)

```typescript
read(options: InterfaceOptions) => Promise<ReadResult>
```

Takes everything waiting in the receive buffer.

| Param         | Type                                                          |
| ------------- | ------------------------------------------------------------- |
| **`options`** | <code><a href="#interfaceoptions">InterfaceOptions</a></code> |

**Returns:** <code>Promise&lt;<a href="#readresult">ReadResult</a>&gt;</code>

--------------------


### readUntil(...)

```typescript
readUntil(options: DelimiterOptions) => Promise<ReadResult>
```

Takes the receive buffer up to and including the delimiter, or nothing when the delimiter has not arrived.

| Param         | Type                                                          |
| ------------- | ------------------------------------------------------------- |
| **`options`** | <code><a href="#delimiteroptions">DelimiterOptions</a></code> |

**Returns:** <code>Promise&lt;<a href="#readresult">ReadResult</a>&gt;</code>

--------------------


### clear(...)

```typescript
clear(options: InterfaceOptions) => Promise<void>
```

Empties the receive buffer.

| Param         | Type                                                          |
| ------------- | ------------------------------------------------------------- |
| **`options`** | <code><a href="#interfaceoptions">InterfaceOptions</a></code> |

--------------------


### getBluetoothState()

```typescript
getBluetoothState() => Promise<BluetoothState>
```

iOS only: the Bluetooth authorization, the declared accessory protocols and the connected accessories, without
asking for anything.

**Returns:** <code>Promise&lt;<a href="#bluetoothstate">BluetoothState</a>&gt;</code>

--------------------


### checkPermissions()

```typescript
checkPermissions() => Promise<PermissionStatus>
```

What the app may do now, without asking.

**Returns:** <code>Promise&lt;<a href="#permissionstatus">PermissionStatus</a>&gt;</code>

--------------------


### requestPermissions()

```typescript
requestPermissions() => Promise<PermissionStatus>
```

Asks for what finding and connecting devices needs. Android 12 and later: Nearby devices. Android 10 and 11: precise
location, and background location too when the app sets `accessBackgroundLocation` (see the README). Android 9 and
earlier: approximate location. iOS: the Bluetooth permission, which `isEnabled` needs.

**Returns:** <code>Promise&lt;<a href="#permissionstatus">PermissionStatus</a>&gt;</code>

--------------------


### addListener('data', ...)

```typescript
addListener(eventName: 'data', listenerFunc: (event: DataEvent) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                |
| ------------------ | ------------------------------------------------------------------- |
| **`eventName`**    | <code>'data'</code>                                                 |
| **`listenerFunc`** | <code>(event: <a href="#dataevent">DataEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('rawData', ...)

```typescript
addListener(eventName: 'rawData', listenerFunc: (event: RawDataEvent) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                      |
| ------------------ | ------------------------------------------------------------------------- |
| **`eventName`**    | <code>'rawData'</code>                                                    |
| **`listenerFunc`** | <code>(event: <a href="#rawdataevent">RawDataEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('disconnected', ...)

```typescript
addListener(eventName: 'disconnected', listenerFunc: (event: DisconnectedEvent) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                                |
| ------------------ | ----------------------------------------------------------------------------------- |
| **`eventName`**    | <code>'disconnected'</code>                                                         |
| **`listenerFunc`** | <code>(event: <a href="#disconnectedevent">DisconnectedEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('deviceDiscovered', ...)

```typescript
addListener(eventName: 'deviceDiscovered', listenerFunc: (device: BluetoothClassicDevice) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                                           |
| ------------------ | ---------------------------------------------------------------------------------------------- |
| **`eventName`**    | <code>'deviceDiscovered'</code>                                                                |
| **`listenerFunc`** | <code>(device: <a href="#bluetoothclassicdevice">BluetoothClassicDevice</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### removeAllListeners()

```typescript
removeAllListeners() => Promise<void>
```

Removes every listener of this plugin.

--------------------


### Interfaces


#### DevicesResult

| Prop          | Type                                  |
| ------------- | ------------------------------------- |
| **`devices`** | <code>BluetoothClassicDevice[]</code> |


#### BluetoothClassicDevice

A device as `list`, `discoverUnpaired` and the `deviceDiscovered` event report it.

Android fills `id`, `address`, `name` (absent when the device has not told its name) and `class` (the Bluetooth device
class as a number). iOS fills every field: `id` and `address` are both the accessory's `connectionID`, `class` is an
empty string, and `protocols` lists the External Accessory protocol strings the accessory speaks.

| Prop                   | Type                                          |
| ---------------------- | --------------------------------------------- |
| **`id`**               | <code><a href="#deviceid">DeviceId</a></code> |
| **`address`**          | <code><a href="#deviceid">DeviceId</a></code> |
| **`name`**             | <code>string</code>                           |
| **`class`**            | <code>string \| number</code>                 |
| **`manufacturer`**     | <code>string</code>                           |
| **`modelNumber`**      | <code>string</code>                           |
| **`serialNumber`**     | <code>string</code>                           |
| **`firmwareRevision`** | <code>string</code>                           |
| **`hardwareRevision`** | <code>string</code>                           |
| **`protocols`**        | <code>string[]</code>                         |


#### ConnectOptions

| Prop             | Type                                          | Description                                                                                                                                                                                                                                                                | Default            |
| ---------------- | --------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------ |
| **`deviceId`**   | <code><a href="#deviceid">DeviceId</a></code> |                                                                                                                                                                                                                                                                            |                    |
| **`interfaces`** | <code>string[]</code>                         | What to open on the device. Android: RFCOMM service UUIDs, of which the first is used (the serial port profile is `00001101-0000-1000-8000-00805F9B34FB`). iOS: External Accessory protocol strings, every one of which is opened; the accessory has to speak all of them. |                    |
| **`insecure`**   | <code>boolean</code>                          | Android only: open an insecure RFCOMM socket (no authentication and no encryption). Ignored on iOS.                                                                                                                                                                        | <code>false</code> |


#### InterfaceOptions

One open interface of a device. Android ignores `interfaceId` when it looks the connection up (a device has one
connection there) and echoes it back on the events; iOS keeps a session per protocol string and needs it.

| Prop              | Type                                          |
| ----------------- | --------------------------------------------- |
| **`deviceId`**    | <code><a href="#deviceid">DeviceId</a></code> |
| **`interfaceId`** | <code>string</code>                           |


#### ConnectedResult

| Prop            | Type                 |
| --------------- | -------------------- |
| **`connected`** | <code>boolean</code> |


#### EnabledResult

| Prop          | Type                 |
| ------------- | -------------------- |
| **`enabled`** | <code>boolean</code> |


#### DelimiterOptions

| Prop            | Type                | Description                                                                                                    |
| --------------- | ------------------- | -------------------------------------------------------------------------------------------------------------- |
| **`delimiter`** | <code>string</code> | The text that ends a message, `\n` for a reader that sends a line per tag. It is included in what is returned. |


#### WriteOptions

| Prop       | Type                | Description                                                                 |
| ---------- | ------------------- | --------------------------------------------------------------------------- |
| **`data`** | <code>string</code> | The bytes to send, base64 encoded (see `bytesToBase64` and `textToBase64`). |


#### AvailableResult

| Prop            | Type                | Description                                                                 |
| --------------- | ------------------- | --------------------------------------------------------------------------- |
| **`available`** | <code>number</code> | What is waiting in the receive buffer: characters on Android, bytes on iOS. |


#### ReadResult

| Prop       | Type                | Description                                                            |
| ---------- | ------------------- | ---------------------------------------------------------------------- |
| **`data`** | <code>string</code> | The text read, decoded as UTF-8. Empty when there was nothing to read. |


#### BluetoothState

| Prop                       | Type                  | Description                                                                                               |
| -------------------------- | --------------------- | --------------------------------------------------------------------------------------------------------- |
| **`authorization`**        | <code>string</code>   | The app's Bluetooth authorization: `notDetermined`, `restricted`, `denied`, `allowedAlways` or `unknown`. |
| **`supportedProtocols`**   | <code>string[]</code> | The External Accessory protocols the app declares in `UISupportedExternalAccessoryProtocols`.             |
| **`connectedAccessories`** | <code>number</code>   | How many accessories iOS reports connected, whichever app they belong to.                                 |
| **`systemVersion`**        | <code>string</code>   | The iOS version.                                                                                          |


#### PermissionStatus

| Prop            | Type                                                        | Description                                                                                                        |
| --------------- | ----------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| **`bluetooth`** | <code><a href="#permissionstate">PermissionState</a></code> | Android 12 and later: Nearby devices (`BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT`). iOS: the Bluetooth authorization. |
| **`location`**  | <code><a href="#permissionstate">PermissionState</a></code> | Android 11 and earlier, where finding devices needs location. Always `granted` where it is not needed.             |


#### PluginListenerHandle

| Prop         | Type                                      |
| ------------ | ----------------------------------------- |
| **`remove`** | <code>() =&gt; Promise&lt;void&gt;</code> |


#### DataEvent

A delimited message, delivered after `subscribe`.

| Prop              | Type                                          | Description                                            |
| ----------------- | --------------------------------------------- | ------------------------------------------------------ |
| **`deviceId`**    | <code><a href="#deviceid">DeviceId</a></code> |                                                        |
| **`interfaceId`** | <code>string</code>                           |                                                        |
| **`data`**        | <code>string</code>                           | The message including its delimiter, decoded as UTF-8. |


#### RawDataEvent

Bytes as they were read from the device, delivered after `subscribeRaw`.

| Prop              | Type                                          | Description                |
| ----------------- | --------------------------------------------- | -------------------------- |
| **`deviceId`**    | <code><a href="#deviceid">DeviceId</a></code> |                            |
| **`interfaceId`** | <code>string</code>                           |                            |
| **`data`**        | <code>string</code>                           | The bytes, base64 encoded. |


#### DisconnectedEvent

A connection that `connect` opened has ended, because it was lost or because `disconnect` closed it.

| Prop              | Type                                          | Description                                                                       |
| ----------------- | --------------------------------------------- | --------------------------------------------------------------------------------- |
| **`deviceId`**    | <code><a href="#deviceid">DeviceId</a></code> |                                                                                   |
| **`interfaceId`** | <code>string</code>                           | The interface that ended: the RFCOMM UUID on Android, the protocol string on iOS. |
| **`message`**     | <code>string</code>                           | `Device connection was lost` or `Device was manually disconnected`.               |


### Type Aliases


#### DeviceId

Identifies a device. On Android it is the device's MAC address (`00:11:22:33:44:55`); on iOS it is the
`connectionID` iOS gives a connected External Accessory, a number.

<code>string | number</code>


#### PermissionState

<code>'prompt' | 'prompt-with-rationale' | 'granted' | 'denied'</code>

</docgen-api>
