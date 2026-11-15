import { WebPlugin } from '@capacitor/core';

import type {
  AvailableResult,
  BluetoothClassicPlugin,
  BluetoothState,
  ConnectedResult,
  DevicesResult,
  EnabledResult,
  PermissionStatus,
  ReadResult,
} from './definitions';

/** A browser has no Bluetooth Classic: every call rejects with Capacitor's `UNAVAILABLE`. */
export class BluetoothClassicWeb extends WebPlugin implements BluetoothClassicPlugin {
  private fail(): never {
    throw this.unavailable('Bluetooth Classic is not available on the web.');
  }

  async list(): Promise<DevicesResult> {
    this.fail();
  }

  async discoverUnpaired(): Promise<DevicesResult> {
    this.fail();
  }

  async connect(): Promise<void> {
    this.fail();
  }

  async disconnect(): Promise<void> {
    this.fail();
  }

  async disconnectAll(): Promise<void> {
    this.fail();
  }

  async isConnected(): Promise<ConnectedResult> {
    this.fail();
  }

  async isEnabled(): Promise<EnabledResult> {
    this.fail();
  }

  async enable(): Promise<void> {
    this.fail();
  }

  async showBluetoothSettings(): Promise<void> {
    this.fail();
  }

  async subscribe(): Promise<void> {
    this.fail();
  }

  async unsubscribe(): Promise<void> {
    this.fail();
  }

  async subscribeRaw(): Promise<void> {
    this.fail();
  }

  async unsubscribeRaw(): Promise<void> {
    this.fail();
  }

  async write(): Promise<void> {
    this.fail();
  }

  async available(): Promise<AvailableResult> {
    this.fail();
  }

  async read(): Promise<ReadResult> {
    this.fail();
  }

  async readUntil(): Promise<ReadResult> {
    this.fail();
  }

  async clear(): Promise<void> {
    this.fail();
  }

  async getBluetoothState(): Promise<BluetoothState> {
    this.fail();
  }

  async checkPermissions(): Promise<PermissionStatus> {
    this.fail();
  }

  async requestPermissions(): Promise<PermissionStatus> {
    this.fail();
  }
}
