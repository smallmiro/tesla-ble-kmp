import XCTest
@testable import TeslableSample

/// SFR-019: 키 별명은 폰 안(UserDefaults)에만 있고 키 ID(공개키 SHA-1 16진수)로 찾는다.
final class KeyNicknameTests: XCTestCase {
    private var suiteName = ""
    private var defaults: UserDefaults!

    // 가짜 키 ID(공개키 SHA-1 16진수 40자 형식)
    private let idA = "0123456789abcdef0123456789abcdef01234567"
    private let idB = "fedcba9876543210fedcba9876543210fedcba98"

    override func setUp() {
        super.setUp()
        suiteName = "KeyNicknameTests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        super.tearDown()
    }

    private func makeStore() -> KeyNicknameStore { KeyNicknameStore(defaults: defaults) }

    // MARK: normalise

    func testNormaliseTrimsSpacesAndNewlines() {
        XCTAssertEqual(KeyNickname.normalise("  아빠 폰 \n"), .name("아빠 폰"))
    }

    func testNormaliseKeepsInnerSpaces() {
        XCTAssertEqual(KeyNickname.normalise("내 아이폰 15"), .name("내 아이폰 15"))
    }

    func testNormaliseEmptyOrWhitespaceMeansRemove() {
        XCTAssertEqual(KeyNickname.normalise(""), .remove)
        XCTAssertEqual(KeyNickname.normalise("   \t\n "), .remove)
    }

    func testNormaliseAcceptsExactlyMaxLength() {
        let name = String(repeating: "가", count: KeyNickname.maxLength)
        XCTAssertEqual(KeyNickname.maxLength, 32)
        XCTAssertEqual(KeyNickname.normalise(name), .name(name))
    }

    func testNormaliseRejectsOneOverMaxLength() {
        let name = String(repeating: "a", count: KeyNickname.maxLength + 1)
        XCTAssertEqual(KeyNickname.normalise(name), .tooLong)
    }

    func testNormaliseLengthIsMeasuredAfterTrimAndInCharacters() {
        // 앞뒤 공백을 빼고 32자면 통과. 이모지는 한 글자로 센다.
        let name = String(repeating: "😀", count: KeyNickname.maxLength)
        XCTAssertEqual(KeyNickname.normalise("  \(name)  "), .name(name))
    }

    func testClampedCutsAtMaxLengthWithoutSplittingCharacters() {
        let name = String(repeating: "😀", count: KeyNickname.maxLength + 5)
        XCTAssertEqual(KeyNickname.clamped(name), String(repeating: "😀", count: KeyNickname.maxLength))
        XCTAssertEqual(KeyNickname.clamped("짧다"), "짧다")
    }

    // MARK: store

    func testNothingStoredGivesNil() {
        XCTAssertNil(makeStore().nickname(forKeyId: idA))
        XCTAssertTrue(makeStore().all.isEmpty)
    }

    func testSetThenGet() {
        let store = makeStore()
        XCTAssertEqual(store.set("  내 폰 ", forKeyId: idA), .saved("내 폰"))
        XCTAssertEqual(store.nickname(forKeyId: idA), "내 폰")
        XCTAssertNil(store.nickname(forKeyId: idB))
    }

    func testPersistsAcrossStoreInstancesOnSameDefaults() {
        _ = makeStore().set("내 폰", forKeyId: idA)
        XCTAssertEqual(makeStore().nickname(forKeyId: idA), "내 폰")
    }

    func testOverwriteReplacesName() {
        let store = makeStore()
        _ = store.set("첫째", forKeyId: idA)
        XCTAssertEqual(store.set("둘째", forKeyId: idA), .saved("둘째"))
        XCTAssertEqual(store.nickname(forKeyId: idA), "둘째")
    }

    func testEmptyInputRemovesExistingNickname() {
        let store = makeStore()
        _ = store.set("내 폰", forKeyId: idA)
        _ = store.set("다른 폰", forKeyId: idB)
        XCTAssertEqual(store.set("   ", forKeyId: idA), .removed)
        XCTAssertNil(store.nickname(forKeyId: idA))
        XCTAssertEqual(store.nickname(forKeyId: idB), "다른 폰")
    }

    func testEmptyInputWithNothingStoredIsRemovedAndHarmless() {
        let store = makeStore()
        XCTAssertEqual(store.set("", forKeyId: idA), .removed)
        XCTAssertTrue(store.all.isEmpty)
    }

    func testTooLongIsRejectedAndKeepsOldName() {
        let store = makeStore()
        _ = store.set("내 폰", forKeyId: idA)
        let tooLong = String(repeating: "x", count: 33)
        XCTAssertEqual(store.set(tooLong, forKeyId: idA), .tooLong)
        XCTAssertEqual(store.nickname(forKeyId: idA), "내 폰")
    }

    func testRemoveDeletesOnlyThatKey() {
        let store = makeStore()
        _ = store.set("A", forKeyId: idA)
        _ = store.set("B", forKeyId: idB)
        store.remove(forKeyId: idA)
        XCTAssertNil(store.nickname(forKeyId: idA))
        XCTAssertEqual(store.nickname(forKeyId: idB), "B")
    }

    func testKeyIdIsCaseInsensitive() {
        let store = makeStore()
        _ = store.set("내 폰", forKeyId: idA.uppercased())
        XCTAssertEqual(store.nickname(forKeyId: idA), "내 폰")
    }

    func testBlankKeyIdIsNeverStored() {
        let store = makeStore()
        XCTAssertEqual(store.set("내 폰", forKeyId: ""), .noKeyId)
        XCTAssertNil(store.nickname(forKeyId: ""))
        XCTAssertTrue(store.all.isEmpty)
    }

    func testStoredByKeyIdNotBySlot() {
        // 같은 키가 다른 슬롯으로 옮겨져도 이름이 따라온다: 저장소는 슬롯을 모른다.
        let store = makeStore()
        _ = store.set("내 폰", forKeyId: idA)
        XCTAssertEqual(store.all, [idA: "내 폰"])
    }

    func testSeparateDefaultsDoNotShare() {
        let otherName = "KeyNicknameTests-other-\(UUID().uuidString)"
        let other = UserDefaults(suiteName: otherName)!
        defer { other.removePersistentDomain(forName: otherName) }
        _ = makeStore().set("내 폰", forKeyId: idA)
        XCTAssertNil(KeyNicknameStore(defaults: other).nickname(forKeyId: idA))
    }
}
