package app.okoklibre.ble.protocol

/** Protocol families implemented by OKOK Libre. */
enum class ScaleProtocolType {
    /** 0xCA / 0xC0 advertising, 0xCA GATT frames (protocol versions 0x10 / 0x11). */
    OKOK_CLASSIC,

    /** FF F0 xx advertising (xx not in {0x10, 0x11}), standard body-composition GATT profile. */
    OKOK_CLOUD,

    /** FF F0 10 / FF F0 11 advertising, heart-rate capable (0xC4 / 0xC5 frames). */
    OKOK_CLOUD_V4,
}
