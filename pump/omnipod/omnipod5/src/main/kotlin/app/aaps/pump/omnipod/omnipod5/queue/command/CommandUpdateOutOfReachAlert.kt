package app.aaps.pump.omnipod.omnipod5.queue.command

import app.aaps.core.interfaces.queue.CustomCommand

/**
 * Sets the "phone out of reach" pod alert again. Queued by the O5 plugin's own timer, so the
 * alert keeps moving while the loop has nothing to send to the pod.
 */
class CommandUpdateOutOfReachAlert : CustomCommand {

    override val statusDescription = "UPDATE OUT OF REACH ALERT"
}
