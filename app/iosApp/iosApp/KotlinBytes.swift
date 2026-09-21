import Foundation
import SharedUI

/// Kotlin bytes are signed; Data's UInt8 values map straight across.
extension KotlinByteArray {
    static func from(_ data: Data) -> KotlinByteArray {
        let array = KotlinByteArray(size: Int32(data.count))
        for (index, byte) in data.enumerated() {
            array.set(index: Int32(index), value: Int8(bitPattern: byte))
        }
        return array
    }
}

extension Data {
    init(from array: KotlinByteArray) {
        self.init(count: Int(array.size))
        for index in 0..<Int(array.size) {
            self[index] = UInt8(bitPattern: array.get(index: Int32(index)))
        }
    }
}
