import Foundation

/// SFR-019: 키 별명. 차량에는 키 이름 칸이 없어(vcsec `KeyMetadata` 는 `keyFormFactor` 뿐) 이 폰 안에만 저장하고
/// 차량으로는 아무것도 보내지 않는다. 키는 슬롯이 아니라 키 ID(공개키 SHA-1 16진수, `WhitelistEntry.keyIdSha1Hex`)로 찾는다.
/// 공개키 전문이나 VIN 은 다루지 않는다.
enum KeyNickname {
    /// 별명 최대 길이(글자 수).
    static let maxLength = 32

    enum Normalised: Equatable {
        /// 앞뒤 공백을 뺀 별명.
        case name(String)
        /// 비었거나 공백뿐이다: 별명을 지운다.
        case remove
        /// 앞뒤 공백을 뺀 길이가 `maxLength` 를 넘는다.
        case tooLong
    }

    /// 앞뒤 공백·줄바꿈을 빼고 길이를 본다.
    static func normalise(_ input: String) -> Normalised {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return .remove }
        if trimmed.count > maxLength { return .tooLong }
        return .name(trimmed)
    }

    /// 입력 칸용: `maxLength` 글자에서 자른다(글자 단위라 이모지를 쪼개지 않는다).
    static func clamped(_ input: String) -> String {
        input.count > maxLength ? String(input.prefix(maxLength)) : input
    }
}

/// 키 ID → 별명을 `UserDefaults` 한 키 아래 사전으로 저장한다. `UserDefaults` 는 주입한다(시험은 임시 스위트).
struct KeyNicknameStore {
    enum SetResult: Equatable {
        case saved(String)
        case removed
        case tooLong
        /// 키 ID 가 없다(공개키 정보가 없는 항목): 저장하지 않는다.
        case noKeyId
    }

    static let defaultsKey = "keyNicknames"

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    /// 키 ID 는 소문자로 맞춘다.
    private static func canonical(_ keyId: String) -> String? {
        let id = keyId.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return id.isEmpty ? nil : id
    }

    /// 저장된 전체(키 ID → 별명).
    var all: [String: String] {
        defaults.dictionary(forKey: Self.defaultsKey) as? [String: String] ?? [:]
    }

    func nickname(forKeyId keyId: String) -> String? {
        Self.canonical(keyId).flatMap { all[$0] }
    }

    /// 정규화해서 저장한다. 빈 입력은 지운다. 너무 길면 저장하지 않고 이전 별명을 둔다.
    @discardableResult
    func set(_ input: String, forKeyId keyId: String) -> SetResult {
        guard let id = Self.canonical(keyId) else { return .noKeyId }
        switch KeyNickname.normalise(input) {
        case .tooLong:
            return .tooLong
        case .remove:
            remove(forKeyId: id)
            return .removed
        case .name(let name):
            var map = all
            map[id] = name
            defaults.set(map, forKey: Self.defaultsKey)
            return .saved(name)
        }
    }

    func remove(forKeyId keyId: String) {
        guard let id = Self.canonical(keyId) else { return }
        var map = all
        guard map.removeValue(forKey: id) != nil else { return }
        defaults.set(map, forKey: Self.defaultsKey)
    }
}
