package cl.filamentbox.app.net

import cl.filamentbox.app.data.DeviceStatus

fun DeviceStatus.validated(): DeviceStatus {
    require(uv.isFinite() && uv >= 0 && (uvUnit != "V" || uv <= 3.6))
    require(targetRh.isFinite() && targetRh in 10.0..60.0)
    require(hysteresis.isFinite() && hysteresis in 1.0..20.0)
    require(uvAlert.isFinite() && uvAlert in 0.0..11.0)
    // Missing readings stay NaN and are shown as unavailable; never substitute 0.
    for (c in listOf(chamber, outdoor, postSilica)) {
        require((!c.t.isFinite() && !c.rh.isFinite()) || c.valid)
    }
    return this
}
