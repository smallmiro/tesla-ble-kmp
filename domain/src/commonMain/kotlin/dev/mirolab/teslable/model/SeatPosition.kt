// Ported from vehicle-command@a4b43c1 pkg/vehicle/infotainment.go (Apache-2.0) — SeatPosition
package dev.mirolab.teslable.model

/**
 * 좌석 위치(FR-061, FR-063, FR-065). Go `SeatPosition`의 10개 상수와 같은 순서다. `_BACK`은 등받이다(등받이 히터는 일부 Model S에만 있다).
 *
 * 명령마다 받는 좌석이 다르다(Go와 같다): 좌석 히터는 10개 전부, 좌석 쿨러는 [FRONT_LEFT]·[FRONT_RIGHT]만, 자동 좌석 공조는
 * [UNKNOWN]·[FRONT_LEFT]·[FRONT_RIGHT]만 쓴다. 와이어 값은 명령마다 명시적으로 대응하며 선언 순서(`ordinal`)를 와이어 값으로 쓰지 않는다.
 */
public enum class SeatPosition {
    /** 알 수 없는 좌석. Go `SeatUnknown`. */
    UNKNOWN,

    /** 앞줄 왼쪽. Go `SeatFrontLeft`. */
    FRONT_LEFT,

    /** 앞줄 오른쪽. Go `SeatFrontRight`. */
    FRONT_RIGHT,

    /** 둘째 줄 왼쪽. Go `SeatSecondRowLeft`. */
    SECOND_ROW_LEFT,

    /** 둘째 줄 왼쪽 등받이. Go `SeatSecondRowLeftBack`. */
    SECOND_ROW_LEFT_BACK,

    /** 둘째 줄 가운데. Go `SeatSecondRowCenter`. */
    SECOND_ROW_CENTER,

    /** 둘째 줄 오른쪽. Go `SeatSecondRowRight`. */
    SECOND_ROW_RIGHT,

    /** 둘째 줄 오른쪽 등받이. Go `SeatSecondRowRightBack`. */
    SECOND_ROW_RIGHT_BACK,

    /** 셋째 줄 왼쪽. Go `SeatThirdRowLeft`. */
    THIRD_ROW_LEFT,

    /** 셋째 줄 오른쪽. Go `SeatThirdRowRight`. */
    THIRD_ROW_RIGHT,
}
