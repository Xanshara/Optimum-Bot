package com.tibiabot.giveaway

import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.components.ActionRow

import scala.concurrent.ExecutionContext

class GiveawayButtonListener(manager: GiveawayManager)(implicit ec: ExecutionContext)
    extends ListenerAdapter with StrictLogging {

  override def onButtonInteraction(event: ButtonInteractionEvent): Unit = {
    if (event.getComponentId != manager.ENTER_BUTTON_ID) return

    val messageId = event.getMessageIdLong
    val userId    = event.getUser.getIdLong
    val userName  = event.getMember.getEffectiveName

    manager.getGiveaway(messageId) match {

      case None =>
        event.reply("❌ Ten giveaway już się zakończył lub nie istnieje.")
          .setEphemeral(true).queue()

      case Some(g) if g.ended =>
        event.reply("❌ Ten giveaway już się zakończył.")
          .setEphemeral(true).queue()

      case Some(g) =>
        manager.addEntry(messageId, userId, userName) match {

          case None =>
            event.reply("⚠️ Wziąłeś już udział w tym giveawayu!")
              .setEphemeral(true).queue()

          case Some(newCount) =>
            // deferEdit → potem edytujemy przez hook (nie przez getMessage)
            event.deferEdit().queue(_ => {
              val newEmbed = manager.buildActiveEmbed(g, newCount).build()
              event.getHook
                .editOriginalEmbeds(newEmbed)
                .setComponents(ActionRow.of(manager.enterButton()))
                .queue(
                  _ => logger.debug(s"Embed giveaway $messageId zaktualizowany ($newCount uczestników)"),
                  err => logger.warn(s"Nie można zaktualizować embeda $messageId: ${err.getMessage}")
                )
            })
        }
    }
  }
}
