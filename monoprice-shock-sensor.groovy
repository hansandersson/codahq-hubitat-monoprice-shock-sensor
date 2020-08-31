/**
 *  Monoprice Z-Wave Plus Shock Detector PID 15269 v0.1
 *  Ben Rimmasch
 *
 *  Made from pieces of the ST Vision Shock Sensor by krlaframboise
 *
 *  If the Monoprice guys sent you to use this sensor shame on them.
 *
 *    0.1 (03/13/2019)
 *      -  Initial Release
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */
metadata {
  definition(
      name: "Monoprice Shock Sensor", namespace: "codahq-hubitat", author: "Ben Rimmasch",
      importUrl: "https://raw.githubusercontent.com/codahq/hubitat_codahq/master/devicestypes/monoprice-shock-sensor.groovy")
    {
    capability "Sensor"
    capability "Acceleration Sensor"
    capability "Battery"
    capability "Configuration"
    capability "Tamper Alert"
    capability "Refresh"
    capability "Health Check"

    attribute "lastCheckin", "string"
    attribute "lastActivity", "string"
    attribute "lastUpdate", "string"

    fingerprint inClusters: "0x5E,0x22,0x85,0x59,0x80,0x5A,0x7A,0x72,0x71,0x73,0x98,0x86,0x84", mfr: "0109", prod: "2003", model: "0307", deviceJoinName: "Z-Wave Plus Shock Detector"
    fingerprint mfr: "0109", prod: "2003", model: "0307", deviceJoinName: "Monoprice Shock Sensor"
    //to add new fingerprints convert dec manufacturer to hex mfr, dec deviceType to hex prod, and dec deviceId to hex model
  }

  preferences {
    input "checkinInterval", "enum", title: "Check-in Interval",
      description: "Changes the interval that the device checks in for configuration changes",
        defaultValue: checkinIntervalSetting, required: false, displayDuringSetup: true,
          options: checkinIntervalOptions.collect { it.name }
    input "batteryReportingInterval", "enum", title: "Battery Reporting Interval",
      description: "Battery reporting interval cannot be changed but notifications more often than this will be suppressed",
        defaultValue: batteryReportingIntervalSetting, required: false, displayDuringSetup: true,
          options: checkinIntervalOptions.collect { it.name }
    input name: "autoClearTamper", type: "bool", title: "Automatically clear tamper alert without ping", default: false
    input name: "descriptionTextEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: false
    input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
    input name: "traceLogEnable", type: "bool", title: "Enable trace logging", defaultValue: false
  }
}

private logInfo(msg) {
  if (descriptionTextEnable) log.info msg
}

def logDebug(msg) {
  if (logEnable) log.debug msg
}

def logTrace(msg) {
  if (traceLogEnable) log.trace msg
}

// Sets flag so that configuration is updated the next time it wakes up.
def updated() {
  logTrace("updated()")
  if (checkinInterval != device.currentValue("checkinInterval")) {
    state.pendingChanges = true
  }
  sendEvent(name: "lastUpdate", value: convertToLocalTimeString(new Date()), displayed: false, isStateChange: true)
  return []
}

// Initializes the device state when paired and updates the device's configuration.
def configure() {
  logTrace("configure()")
  def cmds = []
  if (!state.isConfigured) {
    sendEvent(createLastActivityEventMap())
    state.isConfigured = true
    logTrace("Waiting 1 second because this is the first time being configured")
    cmds << "delay 1000"
  }

  cmds << wakeUpIntervalSetCmd(checkinIntervalSettingMinutes)

  return cmds
}

// Required for HealthCheck Capability, but doesn't actually do anything because this device sleeps.
def ping() {
  logDebug("ping()")
  sendEvent(name: "tamper", value: "clear")
}

def refresh() {
  logDebug("refresh()")
  sendEvent(name: "tamper", value: "clear")
  sendEvent(name: "acceleration", value: "inactive")
  state.remove("lastCheckin")
  state.remove("lastUpdated")
}

// Processes messages received from device.
def parse(String description) {
  logDebug("parse(String description)")
  logTrace("Description: ${description}")
  def result = []

  def cmd = zwave.parse(description, commandClassVersions)
  if (cmd) {
    result << createEvent(createLastActivityEventMap())
    logDebug("Last Activity: ${device.currentValue("lastActivity")}")
    
    result += zwaveEvent(cmd)
  }
  else {
    log.warn "Unable to parse (or event ignored): $description"
  }
  return result
}

private getCommandClassVersions() {
  [
    0x20: 1,  // Basic		
    0x22: 1,  // Application Status (Model 0308)
    0x30: 2,	// Sensor Binary
    0x59: 1,  // AssociationGrpInfo (Model 0308)
    0x5A: 1,  // DeviceResetLocally (Model 0308)
    0x5E: 2,  // ZwaveplusInfo (Model 0308)
    0x7A: 2,  // Firmware Update MD (Model 0308)
    0x71: 3,  // Alarm v1 or Notification (v4)
    0x72: 2,  // ManufacturerSpecific
    0x73: 1,  // Powerlevel (Model 0308)
    0x80: 1,  // Battery
    0x84: 2,  // WakeUp
    0x85: 2,  // Association
    0x86: 1,  // Version (v2)
    0x98: 1   // Security (Model 0308)
  ]
}

// Updates devices configuration, if needed, and creates the event with the last lastcheckin event.
def zwaveEvent(hubitat.zwave.commands.wakeupv2.WakeUpNotification cmd) {
  logDebug("zwaveEvent(hubitat.zwave.commands.wakeupv2.WakeUpNotification cmd)")
  logTrace("WakeUpNotification: $cmd")
  def cmds = []

  sendEvent(name: "lastCheckin", value: convertToLocalTimeString(new Date()), displayed: false, isStateChange: true)
  logInfo("Check-in: ${device.currentValue("lastCheckin")}")

  if (!state.isConfigured || state.pendingChanges) {
    state.pendingChanges = false
    cmds += configure()
  }

  if (canReportBattery()) {
    cmds << batteryGetCmd()
  }

  cmds << secureCmd(zwave.sensorBinaryV2.sensorBinaryGet(sensorType: zwave.sensorBinaryV2.SENSOR_TYPE_DOOR_WINDOW))
  if (cmds) {
    cmds << "delay 2000"
  }
  cmds << wakeUpNoMoreInfoCmd()

  return response(cmds)
}

private canReportBattery() {
  def reportEveryMS = (batteryReportingIntervalSettingMinutes * 60 * 1000)
  return (!state.lastBatteryReport || ((new Date().time) - state.lastBatteryReport > reportEveryMS))
}

// Creates the event for the battery level.
def zwaveEvent(hubitat.zwave.commands.batteryv1.BatteryReport cmd) {
  logDebug("zwaveEvent(hubitat.zwave.commands.batteryv1.BatteryReport cmd)")
  logTrace("BatteryReport: $cmd")
  def val = (cmd.batteryLevel == 0xFF ? 1 : cmd.batteryLevel)
  if (val > 100) {
    val = 100
  }
  else if (val <= 0) {
    val = 1
  }
  state.lastBatteryReport = new Date().time
  logInfo("Battery: ${val}%")
  return createEvent([name: "battery", value: val, descriptionText: "battery ${val}%"])
}

def zwaveEvent(hubitat.zwave.commands.basicv1.BasicSet cmd) {
  logDebug("zwaveEvent(hubitat.zwave.commands.basicv1.BasicSet cmd)")
  logTrace("BasicSet: $cmd")
  
  if (shouldIgnoreThisActivity(cmd.value)) {
    logTrace("Ignoring activity.")
    ignoreNextActivityFor(null)
    return null
  }
  
  ignoreNextActivityFor(1000)
  def val = cmd.value == 0XFF ? "active" : "inactive"
  logInfo("Acceleration: ${val}")
  return createEvent([name: "acceleration", value: val, descriptionText: "acceleration ${val}"])
}

def zwaveEvent(hubitat.zwave.commands.wakeupv2.WakeUpIntervalReport cmd) {
  logDebug("zwaveEvent(hubitat.zwave.commands.wakeupv2.WakeUpIntervalReport cmd)")
  logTrace("WakeUpIntervalReport: $cmd")
  if (cmd.nodeid == 1) {
    sendEvent(name: "checkInterval", value: cmd.seconds)
  }
  else {
    log.warn "wtf ${cmd}"
  }
  return []
}

// Logs unexpected events from the device.
def zwaveEvent(hubitat.zwave.Command cmd) {
  logDebug("zwaveEvent(hubitat.zwave.Command cmd)")
  logTrace("Command: $cmd")
  logIncompatible(cmd)
  return []
}

def zwaveEvent(hubitat.zwave.commands.notificationv3.NotificationReport cmd) {
  logDebug("zwaveEvent(hubitat.zwave.commands.notificationv3.NotificationReport cmd)")
  logTrace("NotificationReport3: $cmd")
  def result = []
  if (cmd.notificationType == 7 && cmd.v1AlarmType == 3) {
    if (cmd.event == 3 || cmd.event == 0) {
      def val = cmd.v1AlarmLevel == 0xFF ? "detected" : "clear"
      logInfo("Sensor: ${cmd.v1AlarmLevel == 0xFF ? "awake" : "asleep"}")
      def event = createEvent([name: "tamper", value: val, descriptionText: "tamper ${val}"])
      if (cmd.v1AlarmLevel == 0xFF) {
        result << event
        logInfo("Tamper: ${val}")
      }
      else if (autoClearTamper) {
        result << event
        logInfo("Tamper: ${val}")
      }
    }
    else {
      logIncompatible(cmd)
    }
  }
  //We'll throw these out for now and handle it in BasicSet for now
  else if (cmd.notificationType == 7 && cmd.v1AlarmType == 2) {
    if (cmd.eventParametersLength == 1 && cmd.eventParameter[0] == 2) {
      //def val = cmd.v1AlarmLevel == 0xFF ? "active" : "inactive" 
      //logInfo "Acceleration: ${val}"
      //result << createEvent([name: "acceleration", value: val, descriptionText: "acceleration ${val}"]) 
    }
    else if (cmd.event == 2) {
      //def val = "active"
      //logInfo "Acceleration: ${val}"
      //result << createEvent([name: "acceleration", value: val, descriptionText: "acceleration ${val}"]) 
    }
    else {
      logIncompatible(cmd)
    }
  }
  else if (cmd.notificationType == 7 && cmd.v1AlarmType == 3) {
    if (cmd.eventParametersLength == 1 && cmd.eventParameter[0] == 3) {
      //def val = cmd.v1AlarmLevel == 0xFF ? "inactive" : "active"
      //logInfo "Acceleration: ${val}"
      //result << createEvent([name: "acceleration", value: val, descriptionText: "acceleration ${val}"]) 
    }
    else if (cmd.event == 3) {
      //def val = "inactive"
      //logInfo "Acceleration: ${val}"
      //result << createEvent([name: "acceleration", value: val, descriptionText: "acceleration ${val}"]) 
    }
    else {
      logIncompatible(cmd)
    }
  }
  else {
    logIncompatible(cmd)
  }
  return result
}

private createLastActivityEventMap() {
  return [name: "lastActivity", value: convertToLocalTimeString(new Date()), displayed: false]
}


private wakeUpIntervalSetCmd(minutesVal) {
  state.checkinIntervalMinutes = minutesVal
  logTrace("wakeUpIntervalSetCmd(${minutesVal})")

  return secureCmd(zwave.wakeUpV2.wakeUpIntervalSet(seconds: (minutesVal * 60), nodeid: zwaveHubNodeId))
}

private wakeUpNoMoreInfoCmd() {
  return secureCmd(zwave.wakeUpV2.wakeUpNoMoreInformation())
}

private batteryGetCmd() {
  return secureCmd(zwave.batteryV1.batteryGet())
}

private secureCmd(cmd) {
  if (zwaveInfo ?.zw ?.contains("s") || ("0x98" in device.rawDescription ?.split(" "))) {
    return zwave.securityV1.securityMessageEncapsulation().encapsulate(cmd).format()
  }
  else {
    return cmd.format()
  }
}

private getCheckinIntervalSettingMinutes() {
  return convertOptionSettingToInt(checkinIntervalOptions, checkinIntervalSetting) ?: 720
}

private getCheckinIntervalSetting() {
  return settings ?.checkinInterval ?: findDefaultOptionName(checkinIntervalOptions)
}

private getBatteryReportingIntervalSettingMinutes() {
  return convertOptionSettingToInt(checkinIntervalOptions, batteryReportingIntervalSetting) ?: checkinIntervalSettingMinutes
}

private getBatteryReportingIntervalSetting() {
  return settings ?.batteryReportingInterval ?: findDefaultOptionName(checkinIntervalOptions)
}

private getCheckinIntervalOptions() {
  [
    [name: "10 Minutes", value: 10],
    [name: "15 Minutes", value: 15],
    [name: "30 Minutes", value: 30],
    [name: "1 Hour", value: 60],
    [name: "2 Hours", value: 120],
    [name: "3 Hours", value: 180],
    [name: "6 Hours", value: 360],
    [name: "9 Hours", value: 540],
    [name: formatDefaultOptionName("12 Hours"), value: 720],
    [name: "18 Hours", value: 1080],
    [name: "24 Hours", value: 1440]
  ]
}


private convertOptionSettingToInt(options, settingVal) {
  return safeToInt(options ?.find { "${settingVal}" == it.name } ?.value, 0)
}

private formatDefaultOptionName(val) {
  return "${val}${defaultOptionSuffix}"
}

private findDefaultOptionName(options) {
  def option = options ?.find { it.name ?.contains("${defaultOptionSuffix}") }
  return option ?.name ?: ""
}

private getDefaultOptionSuffix() {
  return "   (Default)"
}

private safeToInt(val, defaultVal = -1) {
  return "${val}" ?.isInteger() ? "${val}".toInteger() : defaultVal
}

private convertToLocalTimeString(dt) {
  def timeZoneId = location ?.timeZone ?.ID
	if (timeZoneId) {
    return dt.format("MM/dd/yyyy hh:mm:ss a", TimeZone.getTimeZone(timeZoneId))
  }
  else {
    return "$dt"
  }
}

private shouldIgnoreThisActivity(cmdVal) {
  logTrace("shouldIgnoreThisActivity($cmdVal)")
  if (!state.ignoreNextActivityUntil) {
    logTrace("!state.ignoreNextActivityUntil ==> shouldIgnoreThisActivity($cmdVal) returns false")
    return false
  }
  else if (!cmdVal && (device.currentValue("acceleration") == "inactive")) {
    logTrace("!cmdVal && (device.currentValue(\"acceleration\") == \"inactive\") ==> shouldIgnoreThisActivity($cmdVal) returns true")
    return true
  }
  else if ((cmdVal == 0XFF) && (new Date().time) < state.ignoreNextActivityUntil) {
    logTrace("(cmdVal == 0XFF) && (new Date().time) < state.ignoreNextActivityUntil ==> shouldIgnoreThisActivity($cmdVal) returns true")
    return true
  }
  
  logTrace("default ==> shouldIgnoreThisActivity($cmdVal) returns false")
  return false
}

private ignoreNextActivityFor(waitMil) {
  state.ignoreNextActivityUntil = waitMil ? (new Date().time) + waitMil : null
}

private logIncompatible(cmd) {
  log.error "This is probably not the correct device driver for this device!"
  log.warn "cmd: ${cmd}"
}
