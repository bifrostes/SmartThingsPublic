/**
 *  Copyright 2020 Marco van Es
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
 *  Initial author: LGKahn kahn-st@lgk.com
 *  https://github.com/SmartThingsCommunity/SmartThingsPublic/blob/746b9a6a38b69aa472296fc57fb6227b28c7c0bd/smartapps/lgkapps/lgk-virtual-garage-door.src/lgk-virtual-garage-door.groovy
 *
 *  Author extension: kerbs17
 *  https://github.com/kerbs17/smart-things/blob/master/smartapps/lgkapps/lgk-virtual-garage-door.src/lgk-virtual-garage-door.groovy
 *
 *  ChangeLog:
 *  - Version 0.1 (20-11-2020) - initial release, optimized code + added extra parameters
 *
 *  You need to install a 'Simulated Garage Door Opener' device to use this App.
 *  Change the Device Handler runIn delay, from the default 6 to 1 second, for a quicker respond time when opening + closing the door
*/

definition(
    name        : "BF Virtual Garage Door",
    namespace   : "bifrostes",
    author      : "Marco van Es",
    description : "Sync a Simulated Garage Door Opener with 2 physical devices, a tilt/contact sensor and a switch/relay, to control the actual garage door. It also attempts to double check the door was actually closed in case the beam was crossed.",
    category    : "Convenience",
    iconUrl     : "https://s3.amazonaws.com/smartapp-icons/Meta/garage_contact.png",
    iconX2Url   : "https://s3.amazonaws.com/smartapp-icons/Meta/garage_contact@2x.png"
)

preferences {
    section("Choose physical devices."){
        input "opener", "capability.switch", title: "Switch/relay to open/close garage:", required: true
        input "sensor", "capability.contactSensor", title: "Sensor garage:", required: true
    }

    section("Choose the Virtual Garage Door Device. "){
        input "virtualgd", "capability.doorControl", title: "Virtual Garage Door:", required: true
        input "virtualgdbutton", "capability.contactSensor", title: "Virtual Sensor (same as above):", required: true
    }
        
    section("Warn when garage is open (optional)"){
     input "openThreshold", "number", title: "Warn when open longer than:",description: "Number of minutes", required: false
    }
    
    section("Timeout before checking if the door opened or closed correctly."){
        input "checkTimeout", "number", title: "Door Operation Check Timeout:", required: true, defaultValue: 25
    }
    
    section( "Notifications" ) {
        input("recipients", "contact", title: "Send notifications to") {
            input "sendPushMessage", "enum", title: "Send a push notification.", options: ["Yes", "No"], required: false
            input "phone1", "phone", title: "Send a Text Message:", description: "Telephone number", required: false
        }
    }
}

def installed() {
    //log.debug "App is installed"
    subscribe(sensor, "contact", contactHandler)
    subscribe(virtualgdbutton, "contact", virtualgdcontactHandler)
    synchronize()
 }

def updated() {
    //log.debug "Settings are updated"
    unsubscribe()
    subscribe(sensor, "contact", contactHandler)
    subscribe(virtualgdbutton, "contact", virtualgdcontactHandler)
    synchronize()
}

private synchronize() {
   //log.debug "Physical sensor = $sensor.currentContact"
   //log.debug "Virtual  sensor = $virtualgd.currentContact"
    
   if (sensor.currentContact != virtualgd.currentContact) {
        //log.debug "Changing state of virtual sensor to $sensor.currentContact"
        if (realgdstate == "open") {
            virtualgd.open()
        } else {
            virtualgd.close()
        }
    }
}

def contactHandler(evt) {
    def virtualgdstate = virtualgd.currentContact
    //log.debug "Physical Garage door sensor changed to $evt"

    if("open" == evt.value) {
        log.debug "Contact is in ${evt.value} state"
        virtualgd.open()
        schedule("0 * * * * ?", "doorOpenCheck")
    
    } else if("closed" == evt.value) {
        log.debug "Contact is in ${evt.value} state"
        virtualgd.close()
        if (state.openDoorNotificationSent) {
            mysend("Garage door is now closed")
            state.openDoorNotificationSent = false
        }
        unschedule("doorOpenCheck")
    }
}

// Check if door is open for x minutes and send message
def doorOpenCheck() {
    final thresholdMinutes = openThreshold
    if (thresholdMinutes) {
        def currentState = sensor.contactState
        log.debug "doorOpenCheck"
        if (currentState?.value == "open") {
            log.debug "open for ${now() - currentState.date.time}, openDoorNotificationSent: ${state.openDoorNotificationSent}"
            if (!state.openDoorNotificationSent && now() - currentState.date.time > thresholdMinutes * 60 * 1000) {
                def msg = "Garage has been open for ${thresholdMinutes} minutes"
                log.info msg

                mysend(msg)
                state.openDoorNotificationSent = true
            }
        } else {
            state.openDoorNotificationSent = false
        }
    }
}

/**
*  Virtual Garage Door Switch changed, open/close the actual garage door if state doesn't match
*  Also sets a timeout to verify that the open/close actually happened
*/

def virtualgdcontactHandler(evt) {
    // Virtual Garage Door button is pressed
    def realgdstate = sensor.currentContact
    //log.debug "in virtual gd contact/button handler event = $evt"
    //log.debug "in virtualgd contact handler check timeout = $checkTimeout"

    if ("open" == evt.value) {
        log.debug "Contact is in ${evt.value} state"
        if (realgdstate != "open") {
            log.debug "Virtual Garage Door button is pressed, opening Garage Door"
            mysend("143 Virtual Garage Door button is pressed, opening Garage Door!")   
            opener.on()
            //runIn(checkTimeout, checkIfActuallyOpened)
        }
    }else if ("closed" == evt.value) {
        log.debug "Contact is in ${evt.value} state"
        if (realgdstate != "closed") {
            log.debug "Virtual Garage Door button is pressed, closing Garage Door"
            mysend("152 Virtual Garage Door button is pressed, closing Garage Door!")   
            opener.on()
            //runIn(checkTimeout, checkIfActuallyClosed)
        }
    }
}

private mysend(msg) {
    if (location.contactBookEnabled) {
        log.debug("sending notifications to: ${recipients?.size()}")
        sendNotificationToContacts(msg, recipients)
    } else {
        if (sendPushMessage != "No") {
            //log.debug("sending push message")
            sendPush(msg)
        }
        if (phone1) {
            //log.debug("sending text message")
            sendSms(phone1, msg)
        }
    }
    log.debug msg
}

/**
*  Verify that the actual garage has closed/openend and try a second time if needed
*/
def checkIfActuallyClosed() { 
    def realgdstate = sensor.currentContact
    def virtualgdstate = virtualgd.currentContact
    //log.debug "in checkifopen ... current state=  $realgdstate"
    //log.debug "in checkifopen ... gd state= $virtualgd.currentContact"

    // sync them up if need be set virtual same as actual
    if (realgdstate == "open" && virtualgdstate == "closed") {
        log.debug "opening virtual door as it didnt close.. beam probably crossed"
        mysend("Resetting Virtual Garage Door to Open as door didn't close!")   
        virtualgd.open()
    }   
}

def checkIfActuallyOpened() {
    def realgdstate = sensor.currentContact
    def virtualgdstate = virtualgd.currentContact
    //log.debug "in checkifopen ... current state=  $realgdstate"
    //log.debug "in checkifopen ... gd state= $virtualgd.currentContact"

    // sync them up if need be set virtual same as actual
    if (realgdstate == "closed" && virtualgdstate == "open") {
        log.debug "opening virtual door as it didnt open.. track blocked?"
        mysend("Resetting Virtual Garage Door to Closed as door didn't open!")
        virtualgd.close()
    }   
}
