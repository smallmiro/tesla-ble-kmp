package dev.mirolab.teslable.testing.fixtures

/**
 * Infotainment 조회의 **합성** 벡터(M5 계획 Task A6). 실차에서 캡처한 값이 아니라 Go 빌더와 `car_server.proto`·`vehicle.proto`의 필드 번호로
 * 손으로 인코딩한 hex다. 실차 캡처 골든(NFR-002, #118)은 사용자 실차 항목으로 남아 있고, 들어오면 이 값과 따로 둔다. VIN·위치가 없다.
 */
public object InfotainmentVectors {
    /**
     * Go `GetState(StateCategoryCharge)`의 평문 `CarServer.Action{vehicleAction{getVehicleData{getChargeState{}}}}`.
     * `12 04` vehicleAction(2) | `0a 02` getVehicleData(1) | `12 00` getChargeState(2).
     */
    public const val STATE_CHARGE_REQUEST: String = "12040a021200"

    /**
     * 합성 응답 평문 `CarServer.Response{actionStatus{result: OK}, vehicleData{charge_state{…}}}`(28바이트).
     *
     * - `0a 00`: actionStatus(1){} — result OK(0)는 생략
     * - `12 18`: vehicleData(2) | `1a 16`: charge_state(3)
     * - `0a 02 2a 00`: charging_state(1){Charging(5){}}
     * - `c0 06 50`: charge_limit_soc(104) = 80
     * - `fd 06 00805243`: battery_range(111, float) = 210.5
     * - `90 07 48`: battery_level(114) = 72
     * - `d0 07 00`: charger_power(122) = 0 — oneof라 0도 실린다(없음과 다르다)
     * - `a8 09 10`: charging_amps(149) = 16
     */
    public const val STATE_CHARGE_RESPONSE: String =
        "0a00" + "1218" + "1a16" + "0a022a00" + "c00650" + "fd0600805243" + "900748" + "d00700" + "a80910"
}
