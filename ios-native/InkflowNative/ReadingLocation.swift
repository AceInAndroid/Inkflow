import Foundation

/// Pure location rules shared by stored positions and the native readers.
enum ReadingLocation {
    static func fontSize(_ value: Double?) -> Double {
        guard let value, value.isFinite else { return 20 }
        return min(36, max(14, value))
    }

    static func textOffset(_ offset: Int, in source: NSString) -> Int {
        let bounded = min(max(0, offset), source.length)
        guard bounded < source.length else { return bounded }
        // NSString's composed-sequence API can expose the LF half of CRLF separately.
        if bounded > 0, source.character(at: bounded) == 0x0A, source.character(at: bounded - 1) == 0x0D {
            return bounded - 1
        }
        return source.rangeOfComposedCharacterSequence(at: bounded).location
    }

    static func nextTextOffset(after offset: Int, in source: NSString) -> Int {
        let start = textOffset(offset, in: source)
        guard start < source.length else { return start }
        if source.character(at: start) == 0x0D, start + 1 < source.length, source.character(at: start + 1) == 0x0A {
            return start + 2
        }
        return NSMaxRange(source.rangeOfComposedCharacterSequence(at: start))
    }

    static func pageIndex(_ index: Int, count: Int) -> Int {
        guard count > 0 else { return 0 }
        return min(max(0, index), count - 1)
    }

    /// Accepts decimal digits in the user's script; rejects signs, fractions and overflow.
    static func pageNumber(_ input: String, count: Int) -> Int? {
        let input = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard count > 0, !input.isEmpty else { return nil }
        var number = 0
        for character in input {
            guard character.unicodeScalars.allSatisfy({ CharacterSet.decimalDigits.contains($0) }),
                  let digit = character.wholeNumberValue else { return nil }
            let product = number.multipliedReportingOverflow(by: 10)
            let sum = product.partialValue.addingReportingOverflow(digit)
            guard !product.overflow, !sum.overflow else { return nil }
            number = sum.partialValue
        }
        return (1...count).contains(number) ? number : nil
    }
}

struct ReaderPageRequest: Equatable {
    let id = UUID()
    let number: Int
    var index: Int { number > 0 ? number - 1 : 0 }
}
