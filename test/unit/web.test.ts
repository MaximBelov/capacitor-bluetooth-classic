import { CapacitorException, ExceptionCode } from '@capacitor/core';
import { describe, expect, it } from 'vitest';

import { BluetoothClassic } from '../../src';
import { BluetoothClassicWeb } from '../../src/web';

// No native bridge on globalThis, so Capacitor reports the platform as 'web' and loads src/web.ts.
describe('on the web', () => {
  it('rejects every call with UNAVAILABLE through the registered plugin', async () => {
    await expect(BluetoothClassic.list()).rejects.toMatchObject({ code: ExceptionCode.Unavailable });
    await expect(BluetoothClassic.isEnabled()).rejects.toMatchObject({ code: ExceptionCode.Unavailable });
  });

  it('rejects with a CapacitorException for each method', async () => {
    const web = new BluetoothClassicWeb();
    const calls: Promise<unknown>[] = [
      web.list(),
      web.discoverUnpaired(),
      web.connect(),
      web.disconnect(),
      web.disconnectAll(),
      web.isConnected(),
      web.isEnabled(),
      web.enable(),
      web.showBluetoothSettings(),
      web.subscribe(),
      web.unsubscribe(),
      web.subscribeRaw(),
      web.unsubscribeRaw(),
      web.write(),
      web.available(),
      web.read(),
      web.readUntil(),
      web.clear(),
      web.getBluetoothState(),
      web.checkPermissions(),
      web.requestPermissions(),
    ];
    for (const call of calls) {
      expect(await call.catch((e: unknown) => e)).toBeInstanceOf(CapacitorException);
    }
  });
});
