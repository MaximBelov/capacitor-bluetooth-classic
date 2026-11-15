import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';

import type { BluetoothClassicPlugin } from '../../src';

// What the native side of Capacitor injects into the web view: a bridge object that makes the platform 'android', the
// headers of the installed plugins, and nativePromise, which every promise method of a plugin goes through.
const methods = [
  'list',
  'discoverUnpaired',
  'connect',
  'disconnect',
  'disconnectAll',
  'isConnected',
  'isEnabled',
  'enable',
  'showBluetoothSettings',
  'subscribe',
  'unsubscribe',
  'subscribeRaw',
  'unsubscribeRaw',
  'write',
  'available',
  'read',
  'readUntil',
  'clear',
  'getBluetoothState',
  'checkPermissions',
  'requestPermissions',
];
const nativePromise = vi.fn<(...args: unknown[]) => Promise<unknown>>(async () => undefined);
const g = globalThis as Record<string, unknown>;

let BluetoothClassic: BluetoothClassicPlugin;

beforeAll(async () => {
  g.androidBridge = {};
  g.Capacitor = {
    PluginHeaders: [{ name: 'BluetoothClassic', methods: methods.map((name) => ({ name, rtype: 'promise' })) }],
    nativePromise,
  };
  ({ BluetoothClassic } = await import('../../src'));
});

afterAll(() => {
  delete g.androidBridge;
  delete g.Capacitor;
});

describe('on a native platform', () => {
  it('sends each call with its options to the native plugin named BluetoothClassic', async () => {
    nativePromise.mockResolvedValueOnce({ devices: [{ id: 'AA', address: 'AA', name: 'XRS2', class: 7936 }] });
    await expect(BluetoothClassic.list()).resolves.toEqual({
      devices: [{ id: 'AA', address: 'AA', name: 'XRS2', class: 7936 }],
    });
    expect(nativePromise.mock.lastCall?.slice(0, 2)).toEqual(['BluetoothClassic', 'list']);

    await BluetoothClassic.connect({ deviceId: 'AA', interfaces: ['00001101-0000-1000-8000-00805F9B34FB'] });
    expect(nativePromise).toHaveBeenLastCalledWith('BluetoothClassic', 'connect', {
      deviceId: 'AA',
      interfaces: ['00001101-0000-1000-8000-00805F9B34FB'],
    });

    await BluetoothClassic.subscribe({ deviceId: 250001, interfaceId: 'com.example.accessory', delimiter: '\r\n' });
    expect(nativePromise).toHaveBeenLastCalledWith('BluetoothClassic', 'subscribe', {
      deviceId: 250001,
      interfaceId: 'com.example.accessory',
      delimiter: '\r\n',
    });

    await BluetoothClassic.write({ deviceId: 'AA', interfaceId: 'AA', data: 'QUI=' });
    expect(nativePromise).toHaveBeenLastCalledWith('BluetoothClassic', 'write', {
      deviceId: 'AA',
      interfaceId: 'AA',
      data: 'QUI=',
    });
  });

  it('passes on what the native side rejects with, code included', async () => {
    nativePromise.mockRejectedValueOnce(
      Object.assign(new Error('Unable to connect to device'), { code: 'CONNECT_FAILED' }),
    );
    await expect(BluetoothClassic.connect({ deviceId: 'AA', interfaces: ['x'] })).rejects.toMatchObject({
      code: 'CONNECT_FAILED',
    });
  });

  it('reaches every method of the API', async () => {
    for (const method of methods) {
      nativePromise.mockClear();
      await (BluetoothClassic as unknown as Record<string, (o?: unknown) => Promise<unknown>>)[method]({
        deviceId: 'AA',
      });
      expect(nativePromise).toHaveBeenCalledWith('BluetoothClassic', method, expect.anything());
    }
  });
});
