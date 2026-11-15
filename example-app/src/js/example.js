import { BluetoothClassic, base64ToBytes, textToBase64 } from 'capacitor-bluetooth-classic';

// Every call and event is written into #log and kept in window.bluetoothClassicLog, so a person can read it and a script
// can poll it.
window.bluetoothClassicLog = [];
const log = (line) => {
  window.bluetoothClassicLog.push(line);
  document.getElementById('log').textContent = window.bluetoothClassicLog.join('\n');
};
const run = (name, action) => async () => {
  try {
    log(`${name}: ${JSON.stringify(await action())}`);
  } catch (error) {
    log(`${name}: FAIL ${error.code ?? ''} ${error.message}`);
  }
};

const SPP = '00001101-0000-1000-8000-00805F9B34FB';
const devices = new Map();
const showDevices = (list) => {
  const select = document.getElementById('device');
  for (const device of list) {
    devices.set(String(device.address), device);
  }
  select.innerHTML = '';
  for (const [key, device] of devices) {
    const option = document.createElement('option');
    option.value = key;
    option.textContent = `${device.name ?? '(no name)'} ${key}`;
    select.appendChild(option);
  }
  return list;
};
const selected = () => {
  const device = devices.get(document.getElementById('device').value);
  if (!device) throw new Error('no device selected');
  const interfaces = device.protocols?.length ? device.protocols : [SPP];
  return { device, interfaces, interfaceId: device.protocols?.[0] ?? String(device.address) };
};
const delimiter = () => document.getElementById('delimiter').value.replace(/\\r/g, '\r').replace(/\\n/g, '\n');

BluetoothClassic.addListener('data', (event) => log(`data ${event.deviceId} ${JSON.stringify(event.data)}`));
BluetoothClassic.addListener('rawData', (event) => log(`rawData ${event.deviceId} ${base64ToBytes(event.data).length} bytes`));
BluetoothClassic.addListener('disconnected', (event) => log(`disconnected ${event.deviceId} ${event.message}`));
BluetoothClassic.addListener('deviceDiscovered', (device) => {
  log(`deviceDiscovered ${JSON.stringify(device)}`);
  showDevices([device]);
});

const on = (id, handler) => document.getElementById(id).addEventListener('click', handler);
on('permissions', run('requestPermissions', () => BluetoothClassic.requestPermissions()));
on('enabled', run('isEnabled', () => BluetoothClassic.isEnabled()));
on('list', run('list', async () => showDevices((await BluetoothClassic.list()).devices)));
on('discover', run('discoverUnpaired', async () => showDevices((await BluetoothClassic.discoverUnpaired()).devices)));
on(
  'connect',
  run('connect', async () => {
    const { device, interfaces, interfaceId } = selected();
    await BluetoothClassic.connect({ deviceId: device.address, interfaces });
    await BluetoothClassic.subscribe({ deviceId: device.address, interfaceId, delimiter: delimiter() });
    await BluetoothClassic.subscribeRaw({ deviceId: device.address, interfaceId });
    return BluetoothClassic.isConnected({ deviceId: device.address, interfaceId });
  }),
);
on(
  'write',
  run('write', () => {
    const { device, interfaceId } = selected();
    const data = textToBase64(document.getElementById('command').value + delimiter());
    return BluetoothClassic.write({ deviceId: device.address, interfaceId, data });
  }),
);
on(
  'disconnect',
  run('disconnect', () => {
    const { device, interfaceId } = selected();
    return BluetoothClassic.disconnect({ deviceId: device.address, interfaceId });
  }),
);
