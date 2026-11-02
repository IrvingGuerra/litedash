package com.irvingguerra.litedash.feature.obd

/**
 * Qué significa un código de falla, en palabras de todos los días. Solo los genéricos
 * más comunes (los que empiezan con P0); de los demás se dice de qué sistema son.
 */
fun describe(code: String, spanish: Boolean): String {
    COMMON[code]?.let { return if (spanish) it.first else it.second }
    val misfire = Regex("P030([1-8])").matchEntire(code)
    if (misfire != null) {
        val n = misfire.groupValues[1]
        return if (spanish) "Falla de encendido en el cilindro $n" else "Cylinder $n misfire"
    }
    return system(code, spanish)
}

/** El sistema por la primera letra y el tercer carácter (P0 1xx = aire y combustible…). */
private fun system(code: String, spanish: Boolean): String {
    val maker = code.getOrNull(1) != '0'
    val text = when (code.firstOrNull()) {
        'B' -> if (spanish) "Carrocería" else "Body"
        'C' -> if (spanish) "Chasis (frenos, suspensión)" else "Chassis (brakes, suspension)"
        'U' -> if (spanish) "Comunicación entre computadoras" else "Network communication"
        else -> when (code.getOrNull(2)) {
            '1', '2' -> if (spanish) "Mezcla de aire y combustible" else "Fuel and air metering"
            '3' -> if (spanish) "Encendido o falla de cilindros" else "Ignition or misfire"
            '4' -> if (spanish) "Control de emisiones" else "Emission controls"
            '5' -> if (spanish) "Velocidad y ralentí" else "Speed and idle control"
            '6' -> if (spanish) "Computadora del motor" else "Engine computer"
            '7', '8', '9' -> if (spanish) "Transmisión" else "Transmission"
            else -> if (spanish) "Motor" else "Engine"
        }
    }
    return if (maker) "$text · " + (if (spanish) "código de la marca" else "manufacturer code") else text
}

private val COMMON = mapOf(
    "P0011" to ("Tiempo del árbol de levas adelantado (banco 1)" to "Camshaft timing over-advanced (bank 1)"),
    "P0016" to ("Cigüeñal y árbol de levas desfasados" to "Crankshaft/camshaft correlation"),
    "P0030" to ("Calentador del sensor de oxígeno (banco 1, sensor 1)" to "O2 sensor heater circuit (bank 1, sensor 1)"),
    "P0036" to ("Calentador del sensor de oxígeno (banco 1, sensor 2)" to "O2 sensor heater circuit (bank 1, sensor 2)"),
    "P0101" to ("Sensor de flujo de aire (MAF) fuera de rango" to "Mass air flow sensor range/performance"),
    "P0102" to ("Sensor de flujo de aire (MAF): señal baja" to "Mass air flow sensor low input"),
    "P0113" to ("Sensor de temperatura del aire de admisión: señal alta" to "Intake air temperature sensor high input"),
    "P0117" to ("Sensor de temperatura del motor: señal baja" to "Coolant temperature sensor low input"),
    "P0118" to ("Sensor de temperatura del motor: señal alta" to "Coolant temperature sensor high input"),
    "P0121" to ("Sensor de posición del acelerador fuera de rango" to "Throttle position sensor range/performance"),
    "P0128" to ("El motor tarda en calentar (termostato)" to "Coolant thermostat below regulating temperature"),
    "P0131" to ("Sensor de oxígeno: voltaje bajo (banco 1, sensor 1)" to "O2 sensor low voltage (bank 1, sensor 1)"),
    "P0133" to ("Sensor de oxígeno lento (banco 1, sensor 1)" to "O2 sensor slow response (bank 1, sensor 1)"),
    "P0135" to ("Calentador del sensor de oxígeno (banco 1, sensor 1)" to "O2 sensor heater circuit (bank 1, sensor 1)"),
    "P0141" to ("Calentador del sensor de oxígeno (banco 1, sensor 2)" to "O2 sensor heater circuit (bank 1, sensor 2)"),
    "P0171" to ("Mezcla pobre (banco 1): entra aire o falta gasolina" to "System too lean (bank 1)"),
    "P0172" to ("Mezcla rica (banco 1): sobra gasolina" to "System too rich (bank 1)"),
    "P0174" to ("Mezcla pobre (banco 2)" to "System too lean (bank 2)"),
    "P0175" to ("Mezcla rica (banco 2)" to "System too rich (bank 2)"),
    "P0300" to ("Falla de encendido en varios cilindros" to "Random/multiple cylinder misfire"),
    "P0325" to ("Sensor de detonación (banco 1)" to "Knock sensor circuit (bank 1)"),
    "P0335" to ("Sensor de posición del cigüeñal" to "Crankshaft position sensor circuit"),
    "P0340" to ("Sensor de posición del árbol de levas" to "Camshaft position sensor circuit"),
    "P0401" to ("Flujo de recirculación de gases (EGR) insuficiente" to "EGR flow insufficient"),
    "P0420" to ("Catalizador con baja eficiencia (banco 1)" to "Catalyst efficiency below threshold (bank 1)"),
    "P0430" to ("Catalizador con baja eficiencia (banco 2)" to "Catalyst efficiency below threshold (bank 2)"),
    "P0440" to ("Sistema de vapores de gasolina (EVAP)" to "Evaporative emission system"),
    "P0442" to ("Fuga chica en vapores de gasolina (EVAP): revisa el tapón" to "EVAP small leak detected"),
    "P0446" to ("Válvula de ventilación de vapores (EVAP)" to "EVAP vent control circuit"),
    "P0455" to ("Fuga grande en vapores de gasolina (EVAP): tapón flojo o abierto" to "EVAP large leak detected"),
    "P0456" to ("Fuga muy chica en vapores de gasolina (EVAP)" to "EVAP very small leak detected"),
    "P0500" to ("Sensor de velocidad del auto" to "Vehicle speed sensor"),
    "P0505" to ("Control de ralentí" to "Idle air control system"),
    "P0506" to ("Ralentí más bajo de lo normal" to "Idle speed lower than expected"),
    "P0507" to ("Ralentí más alto de lo normal" to "Idle speed higher than expected"),
    "P0562" to ("Voltaje bajo del sistema (batería o alternador)" to "System voltage low"),
    "P0563" to ("Voltaje alto del sistema" to "System voltage high"),
    "P0605" to ("Falla interna de la computadora del motor" to "Internal control module error"),
    "P0700" to ("La transmisión reporta una falla (revisa sus códigos)" to "Transmission control system malfunction"),
    "P0715" to ("Sensor de velocidad de entrada de la transmisión" to "Input/turbine speed sensor"),
    "P0740" to ("Embrague del convertidor de torque" to "Torque converter clutch circuit"),
    "U0100" to ("Se perdió la comunicación con la computadora del motor" to "Lost communication with ECM/PCM"),
)
