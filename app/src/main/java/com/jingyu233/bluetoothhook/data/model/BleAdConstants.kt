package com.jingyu233.bluetoothhook.data.model

/**
 * BLE 广播数据常量映射（UUID 名称、制造商 ID、外观分类、Flags 解释）
 */
object BleAdConstants {

    // ── 16-bit GATT Service UUIDs → 名称 ────────────────────
    private val uuid16Names = mapOf(
        0x1800 to "Generic Access",
        0x1801 to "Generic Attribute",
        0x1802 to "Immediate Alert",
        0x1803 to "Link Loss",
        0x1804 to "Tx Power",
        0x1805 to "Current Time",
        0x1806 to "Reference Time Update",
        0x1807 to "Next DST Change",
        0x1808 to "Glucose",
        0x1809 to "Health Thermometer",
        0x180A to "Device Information",
        0x180D to "Heart Rate",
        0x180E to "Phone Alert Status",
        0x180F to "Battery Service",
        0x1810 to "Blood Pressure",
        0x1811 to "Alert Notification",
        0x1812 to "Human Interface Device",
        0x1813 to "Scan Parameters",
        0x1814 to "Running Speed and Cadence",
        0x1815 to "Automation IO",
        0x1816 to "Cycling Speed and Cadence",
        0x1818 to "Cycling Power",
        0x1819 to "Location and Navigation",
        0x181A to "Environmental Sensing",
        0x181B to "Body Composition",
        0x181C to "User Data",
        0x181D to "Weight Scale",
        0x181E to "Bond Management",
        0x181F to "Continuous Glucose Monitor",
        0x1820 to "Internet Protocol Support",
        0x1821 to "Indoor Positioning",
        0x1822 to "Pulse Oximeter",
        0x1823 to "HTTP Proxy",
        0x1824 to "Transport Discovery",
        0x1825 to "Object Transfer",
        0x1826 to "Fitness Machine",
        0x1827 to "Mesh Provisioning",
        0x1828 to "Mesh Proxy",
        0x1829 to "Reconnection Configuration",
        0x183B to "Constellation",
        0x183C to "Telephone Bearer",
        0x183E to "Physical Activity Monitor",  // 0x183E = Physical Activity Monitor (per Bluetooth SIG); 0x1847 is Device Time
        0x1843 to "Audio Input Control",
        0x1844 to "Volume Control",
        0x1845 to "Volume Offset Control",
        0x1846 to "Coordinated Set Identification",
        0x1847 to "Device Time",
        0x1848 to "Media Control",
        0x1849 to "Generic Media Control",
        0x184A to "Constant Tone Extension",
        0x184B to "Telephone Bearer",
        0x184C to "Generic Telephone Bearer",
        0x184D to "Microphone Control",
        0x184E to "Audio Stream Control",
        0x184F to "Broadcast Audio Scan",
        0x1850 to "Published Audio Capabilities",
        0x1851 to "Basic Audio",
        0x1852 to "Common Audio",
        0x1853 to "Hearing Access",
        0x1854 to "Telephony and Media Audio",
        0x1855 to "Public Broadcast",
        0x1856 to "Audio Stream",
        0x1857 to "Microphone Control",
        0x1858 to "Audio Input Control",
        0x1859 to "Volume Control",
        0xFEAA to "Eddystone",
        0xFEF5 to "Google Fast Pair",
        0xFD6F to "Matter (Wi-Fi)",
        0xFDA6 to "Matter (Thread)",
    )

    /** 根据 16-bit UUID 返回服务名称 */
    fun uuid16Name(uuid: Int): String? = uuid16Names[uuid]

    /**
     * 解析 16-bit UUID 列表 hex，返回 [名称, UUID] 列表
     * @param dataBytes  AD 数据段字节（不含类型和长度）
     * @param isComplete 是否完整列表
     */
    fun parseUuid16List(dataBytes: ByteArray, isComplete: Boolean): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        var i = 0
        while (i + 1 < dataBytes.size) {
            val uuid = (dataBytes[i].toInt() and 0xFF) or
                       ((dataBytes[i + 1].toInt() and 0xFF) shl 8)
            val name = uuid16Name(uuid) ?: "Unknown"
            val uuidStr = "0x${uuid.toString(16).uppercase().padStart(4, '0')}"
            result.add(name to uuidStr)
            i += 2
        }
        return result
    }

    /**
     * 解析 32-bit UUID 列表 hex
     */
    fun parseUuid32List(dataBytes: ByteArray): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        while (i + 3 < dataBytes.size) {
            val uuid = ((dataBytes[i].toInt() and 0xFF).toLong()) or
                       ((dataBytes[i + 1].toInt() and 0xFF).toLong() shl 8) or
                       ((dataBytes[i + 2].toInt() and 0xFF).toLong() shl 16) or
                       ((dataBytes[i + 3].toInt() and 0xFF).toLong() shl 24)
            result.add("0x${uuid.toString(16).uppercase().padStart(8, '0')}")
            i += 4
        }
        return result
    }

    /**
     * 解析 128-bit UUID 列表 hex（返回标准 UUID 格式）
     */
    fun parseUuid128List(dataBytes: ByteArray): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        while (i + 15 < dataBytes.size) {
            // BLE 传输使用小端序
            val parts = mutableListOf<String>()
            for (j in 0..15) {
                parts.add("%02X".format(dataBytes[i + j].toInt() and 0xFF))
            }
            // 格式: XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX
            val uuid = "${parts[3]}${parts[2]}${parts[1]}${parts[0]}-" +
                       "${parts[5]}${parts[4]}-" +
                       "${parts[7]}${parts[6]}-" +
                       "${parts[8]}${parts[9]}-" +
                       parts.drop(10).joinToString("")
            result.add(uuid)
            i += 16
        }
        return result
    }

    // ── 制造商 ID (Company Identifier) → 名称 ──────────────
    private val companyNames = mapOf(
        0x0001 to "Nokia",
        0x0002 to "IBM",
        0x0006 to "Microsoft",
        0x000C to "Digianswer",
        0x0012 to "Mitsubishi",
        0x0019 to "Accel Semiconductor",
        0x0020 to "Broadcom",
        0x0024 to "Belkin",
        0x0025 to "Sony Ericsson",
        0x002E to "Hewlett-Packard",
        0x0030 to "Seiko Epson",
        0x0036 to "Intel",
        0x0037 to "CSR",
        0x003C to "Apple",
        0x0043 to "Qualcomm",
        0x0045 to "Texas Instruments",
        0x0046 to "Toshiba",
        0x0049 to "Samsung",
        0x004A to "Renesas",
        0x004D to "Nike",
        0x004E to "Fitbit (Google)",
        0x0050 to "Symbian",
        0x0051 to "NXP",
        0x0055 to "Datalogic",
        0x0059 to "Nordic Semiconductor",
        0x005C to "Chicony Electronics",
        0x005D to "Sony",
        0x0060 to "Logitech",
        0x0065 to "NVIDIA",
        0x006B to "Snapchat",
        0x006E to "Zebra Technologies",
        0x0075 to "Garmin",
        0x0078 to "Nintendo",
        0x0081 to "Hitachi",
        0x0083 to "Panasonic",
        0x0086 to "Fossil",
        0x0087 to "SkyLab",
        0x0088 to "AmbiCom",
        0x008B to "Vizio",
        0x008F to "Lenovo",
        0x0090 to "HTC",
        0x009A to "Bose",
        0x009E to "Plantronics (Poly)",
        0x00A1 to "Cardo Systems",
        0x00B0 to "Nest Labs (Google)",
        0x00B4 to "St. Jude Medical",
        0x00BB to "Polar Electro",
        0x00C5 to "Tile",
        0x00D6 to "Canon",
        0x00E0 to "Xiaomi",
        0x00E1 to "Oculus VR (Meta)",
        0x00E2 to "Huawei / Honor",
        0x00E5 to "Starbucks",
        0x00E7 to "DJI",
        0x00F2 to "Harman",
        0x00F4 to "OMRON",
        0x00FE to "Stryker",
        0x0110 to "OnePlus",
        0x0111 to "Shenzhen Huizhong Technology",
        0x0117 to "LG Electronics",
        0x0120 to "Realtek",
        0x0129 to "Signify (Philips Hue)",
        0x012C to "Sennheiser",
        0x0130 to "Jawbone",
        0x0131 to "3M",
        0x0133 to "Juniper Systems",
        0x0136 to "Ace Sensors",
        0x0139 to "Dolby",
        0x0140 to "Yamaha",
        0x0141 to "Pioneer",
        0x0142 to "JVCKENWOOD",
        0x0146 to "Acer",
        0x0149 to "Dell",
        0x014E to "Surge Cloud",
        0x0157 to "Wyze Labs",
        0x0166 to "Bluetooth SiG (iBeacon)",
        0x0169 to "Govia (Formerly GoLink)",
        0x0177 to "Mantracourt Electronics",
        0x0181 to "Trakm8",
        0x0196 to "Mannkind",
        0x0198 to "Rhythm",
        0x01A4 to "Freshtemp",
        0x01B6 to "ON Semiconductor",
        0x01C2 to "TP-Link Technologies",
        0x01CF to "LITE-ON Technology",
        0x01E0 to "Espressif",
        0x01EB to "Jolla",
        0x01F0 to "Analog Devices",
        0x01FF to "Roku",
        0x0214 to "Audio-Technica",
        0x021F to "Cleer",
        0x0221 to "Withings",
        0x0228 to "Zimi Corporation (Xiaomi Ecosystem)",
        0x0240 to "Google",
        0x0246 to "Amazon",
        0x0259 to "Facebook (Meta)",
        0x0260 to "Aplix",
        0x0267 to "Rakuten Kobo",
        0x0269 to "ASUS",
        0x026E to "Cypress Semiconductor (Infineon)",
        0x0285 to "Sony Interactive Entertainment (PlayStation)",
        0x0288 to "Suunto",
        0x0292 to "Swisscom",
        0x02A5 to "Anker",
        0x02AD to "B&O (Bang & Olufsen)",
        0x02B8 to "Biowatch",
        0x02CB to "ALPS ALPINE",
        0x02D4 to "Godo Kaisha IP Bridge 1",
        0x02DD to "Parrot",
        0x02E0 to "GEO Semiconductor",
        0x02EC to "Bluetooth SiG",
        0x030E to "Cypress Semiconductor (Infineon 2)",
        0x0332 to "Rivian",
        0x0337 to "Lumens",
        0x0355 to "IF, LLC (Zigbee Alliance)",
        0x0359 to "Husqvarna AB",
        0x035C to "ATH (Tonal)",
        0x0366 to "Woven Planet (Toyota)",
        0x0375 to "Shoei",
        0x0392 to "Unit 1 (Yuga)",
        0x039C to "Porsche AG",
        0x03A5 to "HM (Expensify)",
        0x03B5 to "Redmond Industrial Group",
        0x03D0 to "Dyson Technology",
        0x03F0 to "KTM",
        0x0440 to "Cerebrum Sensor Technologies",
        0x04A5 to "Silo",
        0x04BC to "Garmin International",
        0x04D0 to "Bayerische Motoren Werke AG (BMW)",
        0x04F8 to "The Qt Company (Tesla)",
        0x0529 to "Xenoma",
        0x0566 to "Byteflies",
        0x059C to "Ford Motor Company",
        0x05B8 to "Starkey Laboratories",
        0x05F0 to "Silicon Laboratories",
    )

    /** 根据公司 ID 返回公司名称 */
    fun companyName(id: Int): String? = companyNames[id]

    /** 解析制造商数据中的公司 ID */
    fun parseManufacturerId(dataBytes: ByteArray): Pair<Int, String>? {
        if (dataBytes.size < 2) return null
        val id = (dataBytes[0].toInt() and 0xFF) or
                 ((dataBytes[1].toInt() and 0xFF) shl 8)
        val name = companyName(id) ?: "Unknown ($id)"
        return id to name
    }

    // ── iBeacon ──────────────────────────────────────────
    /**
     * 检测 iBeacon 数据包。
     * iBeacon 格式: Manufacturer 0x004C (Apple), type 0x02, 长度 0x15,
     *   Proximity UUID (16字节) + Major (2字节) + Minor (2字节) + Tx Power (1字节)
     */
    fun parseIBeacon(dataBytes: ByteArray): IBeaconData? {
        if (dataBytes.size < 25) return null
        val manufId = (dataBytes[0].toInt() and 0xFF) or
                      ((dataBytes[1].toInt() and 0xFF) shl 8)
        if (manufId != 0x004C) return null // Apple
        if (dataBytes[2].toInt() and 0xFF != 0x02) return null // iBeacon type
        if (dataBytes[3].toInt() and 0xFF != 0x15) return null // 21 bytes

        // Proximity UUID (bytes 4-19)
        val uuidBytes = dataBytes.sliceArray(4..19)
        val uuidStr = uuidBytes.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
            .let { hex ->
                "${hex.substring(0, 8)}-${hex.substring(8, 12)}-" +
                "${hex.substring(12, 16)}-${hex.substring(16, 20)}-" +
                hex.substring(20)
            }

        // Major (bytes 20-21)
        val major = ((dataBytes[20].toInt() and 0xFF) shl 8) or
                    (dataBytes[21].toInt() and 0xFF)
        // Minor (bytes 22-23)
        val minor = ((dataBytes[22].toInt() and 0xFF) shl 8) or
                    (dataBytes[23].toInt() and 0xFF)
        // Tx Power (byte 24, signed)
        val txPower = dataBytes[24].toInt()

        return IBeaconData(uuidStr, major, minor, txPower)
    }

    data class IBeaconData(
        val proximityUuid: String,
        val major: Int,
        val minor: Int,
        val txPower: Int
    )

    // ── BLE Flags ────────────────────────────────────────
    /** 解析 BLE Flags (AD type 0x01) */
    fun parseFlags(dataByte: Int): List<String> {
        val flags = mutableListOf<String>()
        if (dataByte and 0x01 != 0) flags.add("LE Limited Discoverable Mode")
        if (dataByte and 0x02 != 0) flags.add("LE General Discoverable Mode")
        if (dataByte and 0x04 != 0) flags.add("BR/EDR Not Supported")
        if (dataByte and 0x08 != 0) flags.add("Simultaneous LE and BR/EDR (Controller)")
        if (dataByte and 0x10 != 0) flags.add("Simultaneous LE and BR/EDR (Host)")
        if (flags.isEmpty()) flags.add("None")
        return flags
    }

    // ── Appearance ───────────────────────────────────────
    private val appearanceCategories = mapOf(
        0 to "Unknown",
        64 to "Phone",
        65 to "Computer",
        66 to "Laptop",
        67 to "Watch (Wristwatch)",
        68 to "Watch (Sports Watch)",
        69 to "Clock",
        70 to "Display",
        71 to "Remote Control",
        72 to "Eye-glasses",
        73 to "Glasses (Augmented Reality)",
        74 to "Ring",
        75 to "Sensor",
        76 to "Medical Monitor",
        77 to "Camera (Photo)",
        78 to "Camera (Video)",
        79 to "Headset",
        80 to "Headphones (In-ear)",
        81 to "Headphones (Over-ear)",
        82 to "Headphones (On-ear)",
        83 to "Portable Audio",
        84 to "Speaker",
        85 to "Speaker (Portable)",
        86 to "Car Audio",
        87 to "Navigation",
        88 to "Telescope",
        89 to "Universal Remote",
        90 to "Smart Display",
        91 to "Hub",
        92 to "TV (Standalone)",
        93 to "TV (Media Player)",
        94 to "Set-top Box",
        95 to "Media Player (Portable)",
        96 to "Media Player (Network)",
        97 to "Projector",
        98 to "Printer",
        99 to "Scanner",
        100 to "Fax",
        101 to "Copier",
        102 to "Multi-function Printer",
        103 to "Bar Code Scanner",
        104 to "RFID Reader",
        105 to "Cash Register",
        106 to "POS Terminal",
        107 to "Scale",
        108 to "Kitchen Scale",
        109 to "Food Thermometer",
        110 to "Humidity Sensor",
        111 to "Motion Sensor",
        112 to "Light Sensor",
        113 to "Force Sensor",
        114 to "Pressure Sensor",
        115 to "Air Quality Sensor",
        116 to "Gas Sensor",
        117 to "Alcohol Sensor",
        118 to "Smoke Detector",
        119 to "Carbon Monoxide Detector",
        120 to "Carbon Dioxide Detector",
        121 to "Water Leak Detector",
        122 to "Vibration Motor",
        123 to "Light Bulb",
        124 to "Light Bulb (Color)",
        125 to "Light Bulb (Color Temperature)",
        126 to "Light Strip",
        127 to "Light Fixture",
        128 to "Dimmer Switch",
        129 to "Outlet",
        130 to "Smart Plug",
        131 to "Power Strip",
        132 to "Extension Cord",
        133 to "Charger",
        134 to "Battery Pack (Power Bank)",
        135 to "Electronic Cigarette",
        136 to "Vape Device",
        137 to "eBike",
        138 to "eScooter",
        139 to "Hoverboard",
        140 to "Self-balancing Scooter",
        141 to "Robot",
        142 to "Robot Vacuum",
        143 to "Drone",
        144 to "Aircraft",
        145 to "Air Conditioner",
        146 to "Heater",
        147 to "Humidifier",
        148 to "Dehumidifier",
        149 to "Air Purifier",
        150 to "Fan",
        151 to "Ceiling Fan",
        152 to "Oven",
        153 to "Microwave",
        154 to "Toaster",
        155 to "Coffee Maker",
        156 to "Coffee Machine",
        157 to "Dishwasher",
        158 to "Washer",
        159 to "Dryer",
        160 to "Refrigerator",
        161 to "Freezer",
        162 to "Thermostat",
        163 to "Window Covering",
        164 to "Door Lock",
        165 to "Garage Door",
        166 to "Gate",
        167 to "Alarm System",
        168 to "Security Camera",
        169 to "Video Doorbell",
        170 to "Smoke Alarm",
        171 to "Flood Alarm",
        172 to "Motion Detector",
        173 to "Glass Break Detector",
        174 to "Contact Sensor",
        175 to "Key Fob",
        176 to "Tag",
        192 to "Pulse Oximeter",
        193 to "Pulse Oximeter (Fingertip)",
        194 to "Pulse Oximeter (Wrist-worn)",
        195 to "Heart Rate Monitor",
        196 to "Heart Rate Monitor (Chest Strap)",
        197 to "Heart Rate Monitor (Armband)",
        198 to "Blood Pressure Monitor",
        199 to "Blood Pressure Monitor (Upper Arm)",
        200 to "Blood Pressure Monitor (Wrist)",
        201 to "Thermometer",
        202 to "Thermometer (Forehead)",
        203 to "Thermometer (Ear)",
        204 to "Weight Scale",
        205 to "Glucose Meter",
        206 to "Glucose Meter (Continuous)",
        207 to "Insulin Pump",
        208 to "Medication Dispenser",
        209 to "Peak Flow Meter",
        210 to "Spirometer",
        211 to "ECG Monitor",
        212 to "EEG Monitor",
        213 to "EMG Monitor",
        214 to "Body Composition Scale",
        215 to "Smart Bed",
        216 to "Fitness Equipment",
        217 to "Treadmill",
        218 to "Elliptical",
        219 to "Stationary Bike",
        220 to "Rowing Machine",
        221 to "Stair Climber",
        222 to "Jump Rope",
        223 to "Yoga Mat",
        224 to "Posture Sensor",
        225 to "Activity Tracker",
        226 to "Step Counter (Pedometer)",
        227 to "Calorie Tracker",
        228 to "Sleep Monitor",
        229 to "Stress Monitor",
        230 to "UV Monitor",
        231 to "Hydration Monitor",
        232 to "Swim Tracker",
        233 to "Golf Tracker",
        234 to "Tennis Tracker",
        235 to "Ski Tracker",
        236 to "Snowboard Tracker",
        237 to "Surfing Tracker",
        238 to "Kayak Tracker",
        239 to "Fishing Tracker",
        240 to "Bicycle Computer",
        241 to "Bicycle Speed Sensor",
        242 to "Bicycle Cadence Sensor",
        243 to "Bicycle Power Sensor",
        244 to "Bicycle Combined Sensor",
        256 to "Location Tracker",
        257 to "Pet Tracker",
        258 to "Luggage Tracker",
        259 to "Key Finder",
        260 to "Wallet Tracker",
        261 to "Umbrella Tracker",
        262 to "Backpack Tracker",
    )

    /** 解析 Appearance (AD type 0x19) */
    fun parseAppearance(value: Int): String {
        val category = value and 0x03FF // lower 10 bits for category
        val subCategory = (value shr 10) and 0x3F // bits 10-15 for sub-category
        val baseName = appearanceCategories[category] ?: "Unknown Category ($category)"
        return if (subCategory > 0) "$baseName (sub: $subCategory)" else baseName
    }

    // ── RSSI 距离估算 ────────────────────────────────────
    /**
     * 基于 RSSI 估算距离（米）。
     * 使用简化路径损耗模型: distance = 10^((TxPower - RSSI) / (10 * N))
     * N=2 为自由空间，N=3-4 为室内
     */
    fun estimateDistanceMeters(rssi: Int, measuredPower: Int = -59, n: Float = 2.5f): Float {
        if (rssi == 0) return -1f
        val ratio = (measuredPower - rssi).toFloat() / (10f * n)
        return Math.pow(10.0, ratio.toDouble()).toFloat()
    }

    /** 距离文字描述 */
    fun distanceDescription(rssi: Int): String {
        if (rssi == 127 || rssi == 0) return "未知"
        val dist = estimateDistanceMeters(rssi)
        return when {
            dist < 0 -> "未知"
            dist < 1f -> "%.0f cm".format(dist * 100)
            dist < 10f -> "%.1f m".format(dist)
            dist < 50f -> "%.0f m".format(dist)
            else -> ">50 m"
        }
    }
}
