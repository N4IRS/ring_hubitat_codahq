/**
 *  Ring Virtual Keypad Driver
 *
 *  Copyright 2019 Ben Rimmasch
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
 *
 *  Change Log:
 *  2019-04-26: Initial
 *  2019-11-15: Import URL
 *  2020-01-11: Fixed motion sensing capabilities (I noticed there is a new impulse type of "keypad.motion" so the websocket
 *                now returns enough information to somewhat reliably do this.  It's like they can hear my thoughts.  As of
 *                this change the active message comes reliably but the inactive message does not.  For this reason I just
 *                schedule off the motion instead.  Maybe at some later date I can change it to turn off when a message is
 *                received.)
 *  2020-02-12: Fixed battery % to show correctly in dashboards
 *
 */

import groovy.json.JsonSlurper

metadata {
  definition(name: "Ring Virtual Keypad", namespace: "codahq-hubitat", author: "Ben Rimmasch",
    importUrl: "https://raw.githubusercontent.com/codahq/ring_hubitat_codahq/master/src/drivers/ring-virtual-keypad.groovy") {
    capability "Sensor"
    capability "Motion Sensor"
    capability "Audio Volume"
    capability "Battery"

    attribute "mode", "string"
    attribute "brightness", "number"

    command "setBrightness", [[name: "Set LED Brightness*", type: "NUMBER", range: "0..100", description: "Choose a value between 0 and 100"]]
  }

  preferences {
    input name: "motionTimeout", type: "number", range: 5..600, title: "Time in seconds before motion resets to inactive", defaultValue: 15
    input name: "descriptionTextEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: false
    input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
    input name: "traceLogEnable", type: "bool", title: "Enable trace logging", defaultValue: false
  }
}

private getVOLUME_INC() {
  return 5 //somebody can make this a preference if they feel strongly about it
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

def setVolume(vol) {
  logDebug "Attempting to set volume."
  vol > 100 ? 100 : vol
  vol < 0 ? 0 : vol
  if (vol == 0 && !isMuted()) {
    state.prevVolume = device.currentValue("volume")
    sendEvent(name: "mute", value: "muted")
  }
  else if (vol != 0 && isMuted()) {
    sendEvent(name: "mute", value: "unmuted")
  }
  else {
    logDebug "No mute/unmute needed..."
  }
  if (device.currentValue("volume") != vol) {
    def data = ["volume": (vol == null ? 50 : vol).toDouble() / 100]
    parent.simpleRequest("setdevice", [zid: device.getDataValue("zid"), dst: null, data: data])
  }
  else {
    logInfo "Already at volume."
    sendEvent(name: "volume", value: device.currentValue("volume"))
  }
}

def volumeUp() {
  logDebug "Attempting to raise volume."
  def nextVol = device.currentValue("volume") + VOLUME_INC
  if (nextVol <= 100) {
    setVolume(nextVol)
  }
  else {
    logInfo "Already max volume."
    sendEvent(name: "volume", value: device.currentValue("volume"))
  }
}

def volumeDown() {
  logDebug "Attempting to lower volume."
  def nextVol = device.currentValue("volume") - VOLUME_INC
  if (nextVol >= 0) {
    setVolume(nextVol)
  }
  else {
    logInfo "Already min volume."
    sendEvent(name: "volume", value: device.currentValue("volume"))
  }
}

def mute() {
  logDebug "Attempting to mute."
  setVolume(0)
}

def unmute() {
  logDebug "Attempting to unmute."
  setVolume(state.prevVolume)
}

private isMuted() {
  return device.currentValue("mute") == "muted"
}

def setBrightness(brightness) {
  logDebug "Attempting to set brightness ${brightness}."
  brightness = brightness > 100 ? 100 : brightness
  brightness = brightness < 0 ? 0 : brightness
  def data = ["brightness": (brightness == null ? 100 : brightness).toDouble() / 100]
  parent.simpleRequest("setdevice", [zid: device.getDataValue("zid"), dst: null, data: data])
}

def refresh() {
  logDebug "Attempting to refresh."
  parent.simpleRequest("refresh", [dst: device.deviceNetworkId])
}

def stopMotion() {
  checkChanged("motion", "inactive")
}

def setValues(deviceInfo) {
  logDebug "updateDevice(deviceInfo)"
  logTrace "deviceInfo: ${deviceInfo}"

  if (deviceInfo.state && deviceInfo.state.volume != null) {
    checkChanged("volume", (deviceInfo.state.volume * 100) as Integer)
  }

  //TODO: probably only when mode changes?
  //if (params.mode && device.currentValue("mode") != params.mode) {
  //  logInfo "Alarm mode for device ${device.label} is ${params.mode}"
  //  sendEvent(name: "mode", value: params.mode)
  //}

  if (deviceInfo.impulseType == "keypad.motion") {
    checkChanged("motion", "active")
    //The inactive message almostm never comes reliably.  for now we'll schedule it off
    unschedule()
    runIn(motionTimeout.toInteger(), stopMotion)
  }
  if (deviceInfo.state && deviceInfo.state.brightness != null) {
    checkChanged("brightness", (deviceInfo.state.brightness * 100) as Integer)
  }
  if (deviceInfo.batteryLevel) {
    checkChanged("battery", deviceInfo.batteryLevel, "%")
  }
  if (deviceInfo.lastUpdate) {
    state.lastUpdate = deviceInfo.lastUpdate
  }
  if (deviceInfo.impulseType) {
    state.impulseType = deviceInfo.impulseType
  }
  if (deviceInfo.lastCommTime) {
    state.signalStrength = deviceInfo.lastCommTime
  }
  if (deviceInfo.nextExpectedWakeup) {
    state.nextExpectedWakeup = deviceInfo.nextExpectedWakeup
  }
  if (deviceInfo.signalStrength) {
    state.signalStrength = deviceInfo.signalStrength
  }
  if (deviceInfo.firmware && device.getDataValue("firmware") != deviceInfo.firmware) {
    device.updateDataValue("firmware", deviceInfo.firmware)
  }
  if (deviceInfo.hardwareVersion && device.getDataValue("hardwareVersion") != deviceInfo.hardwareVersion) {
    device.updateDataValue("hardwareVersion", deviceInfo.hardwareVersion)
  }

}

def checkChanged(attribute, newStatus) {
  checkChanged(attribute, newStatus, null)
}

def checkChanged(attribute, newStatus, unit) {
  if (device.currentValue(attribute) != newStatus) {
    logInfo "${attribute.capitalize()} for device ${device.label} is ${newStatus}"
    sendEvent(name: attribute, value: newStatus, unit: unit)
  }
}

def childParse(type, params = []) {
  logDebug "childParse(type, params)"
  logTrace "type ${type}"
  logTrace "params ${params}"

}
