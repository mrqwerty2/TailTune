import CoreBluetooth

enum TailTuneBLEUUID {
  // Random 128-bit UUIDs owned by TailTune. Keep in sync with Android.
  static let service = CBUUID(string: "73F1B100-7A11-4A6A-9B42-54A14C9E0001")
  static let rx = CBUUID(string: "73F1B100-7A11-4A6A-9B42-54A14C9E0002")
  static let tx = CBUUID(string: "73F1B100-7A11-4A6A-9B42-54A14C9E0003")
}
