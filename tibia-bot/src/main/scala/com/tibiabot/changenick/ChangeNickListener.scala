package com.tibiabot.changenick

import com.tibiabot.Config
import com.tibiabot.tibiadata.TibiaDataClient
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter

import java.awt.Color
import java.time.ZonedDateTime
import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success}

/**
 * Listener obsługujący komendę /changenick z subcommands:
 * - /changenick set old_nick new_name - zmienia nick członka
 * - /changenick refresh - odświeża levele wszystkich członków
 * Pozwala administratorom zmienić nick członków po weryfikacji w Tibii
 */
class ChangeNickListener(
    tibiaDataClient: TibiaDataClient,
    nicknameScheduler: Option[NicknameUpdateScheduler] = None
)(implicit ec: ExecutionContext) extends ListenerAdapter with StrictLogging {

  override def onSlashCommandInteraction(event: SlashCommandInteractionEvent): Unit = {
    if (event.getName == "changenick") {
      // Sprawdź który subcommand
      Option(event.getSubcommandName) match {
        case Some("refresh") => handleRefreshCommand(event)
        case Some("set") | None => handleChangeNickCommand(event) // None dla kompatybilności wstecznej
        case Some(unknown) =>
          event.reply(s"${Config.noEmoji} Nieznana komenda: $unknown").setEphemeral(true).queue()
      }
    }
  }

  /**
   * Obsługuje /changenick refresh - odświeża levele wszystkich członków
   */
  private def handleRefreshCommand(event: SlashCommandInteractionEvent): Unit = {
    event.deferReply(true).queue() // Ephemeral

    val guild = event.getGuild
    if (guild == null) {
      replyError(event, "Ta komenda działa tylko na serwerach!")
      return
    }

    val adminMember = event.getMember
    if (adminMember == null) {
      replyError(event, "Nie mogę znaleźć informacji o Tobie!")
      return
    }

    // Sprawdź uprawnienia
    if (!adminMember.hasPermission(Permission.MANAGE_SERVER) && !adminMember.hasPermission(Permission.NICKNAME_MANAGE)) {
      replyError(event, s"${Config.noEmoji} Nie masz uprawnień do użycia tej komendy!")
      return
    }

    nicknameScheduler match {
      case Some(scheduler) =>
        event.getHook.editOriginal(s"${Config.yesEmoji} Rozpoczynam aktualizację levelów...\n\n_To może potrwać kilka minut._").queue()
        
        logger.info(s"Manual nickname refresh triggered by ${adminMember.getEffectiveName} on ${guild.getName}")
        scheduler.updateGuildNicknames(guild)
        
        // Poczekaj 3 sekundy i wyślij potwierdzenie
        Thread.sleep(3000)
        event.getHook.editOriginal(s"${Config.yesEmoji} Aktualizacja levelów została uruchomiona!\n\n" +
          s"Sprawdzam wszystkie nicki w formacie `emoji Name [level]` i aktualizuję zmiany.\n\n" +
          s"_Sprawdź logi bota aby zobaczyć szczegóły._").queue()
        
      case None =>
        replyError(event, s"${Config.noEmoji} Automatyczna aktualizacja nicków nie jest włączona!")
    }
  }

  /**
   * Obsługuje /changenick set old_nick new_name
   */
  private def handleChangeNickCommand(event: SlashCommandInteractionEvent): Unit = {
    event.deferReply(true).queue() // Ephemeral reply

    val guild = event.getGuild
    if (guild == null) {
      replyError(event, "Ta komenda działa tylko na serwerach!")
      return
    }

    val adminMember = event.getMember
    if (adminMember == null) {
      replyError(event, "Nie mogę znaleźć informacji o Tobie!")
      return
    }

    // Sprawdź uprawnienia administratora
    if (!adminMember.hasPermission(Permission.MANAGE_SERVER) && !adminMember.hasPermission(Permission.NICKNAME_MANAGE)) {
      replyError(event, s"${Config.noEmoji} Nie masz uprawnień do użycia tej komendy!")
      return
    }

    // Pobierz parametry
    val oldNickOption = Option(event.getOption("old_nick"))
    val newNameOption = Option(event.getOption("new_name"))

    if (oldNickOption.isEmpty || newNameOption.isEmpty) {
      replyError(event, s"${Config.noEmoji} Musisz podać oba parametry: old_nick i new_name!")
      return
    }

    val oldNick = oldNickOption.get.getAsString.trim
    val newName = newNameOption.get.getAsString.trim

    if (oldNick.isEmpty || newName.isEmpty) {
      replyError(event, s"${Config.noEmoji} Parametry nie mogą być puste!")
      return
    }

    // Znajdź członka po nicku (case-insensitive)
    logger.info(s"Searching for member with nick: $oldNick")
    
    // Użyj już załadowanego cache (MemberCachePolicy.ALL jest aktywne)
    val matchingMembers = guild.getMembers.asScala
      .filter { member =>
        val effectiveName = member.getEffectiveName.toLowerCase
        val userName = member.getUser.getName.toLowerCase
        effectiveName == oldNick.toLowerCase || userName == oldNick.toLowerCase
      }
      .toList

    if (matchingMembers.isEmpty) {
      replyError(event, s"${Config.noEmoji} Nie znaleziono członka o nicku **$oldNick** na tym serwerze!\n\n" +
        s"Sprawdź czy nick jest poprawny (wielkie/małe litery nie mają znaczenia).")
      return
    }

    if (matchingMembers.size > 1) {
      val memberList = matchingMembers.map(m => s"• ${m.getEffectiveName} (${m.getUser.getName})").mkString("\n")
      replyError(event, s"${Config.noEmoji} Znaleziono więcej niż jednego członka o nicku **$oldNick**:\n\n$memberList\n\n" +
        s"Użyj dokładnego nicku Discord!")
      return
    }

    val targetMember = matchingMembers.head

    // Sprawdź czy bot może zmienić nick (hierarchia ról)
    if (!guild.getSelfMember.canInteract(targetMember)) {
      replyError(event, s"${Config.noEmoji} Nie mogę zmienić nicku tego członka! Moja rola musi być wyżej w hierarchii.")
      return
    }

    // Sprawdź czy admin może zmienić nick (hierarchia ról)
    if (!adminMember.canInteract(targetMember)) {
      replyError(event, s"${Config.noEmoji} Nie możesz zmienić nicku tego członka! Twoja rola musi być wyżej w hierarchii.")
      return
    }

    logger.info(s"Checking character '$newName' in TibiaData API...")

    // Sprawdź czy postać istnieje w Tibii
    tibiaDataClient.getCharacter(newName).onComplete {
      case Success(Right(characterResponse)) =>
        val character = characterResponse.character.character

        if (character.name == "") {
          replyError(event, s"${Config.noEmoji} Postać **$newName** nie istnieje w Tibii!")
          return
        }

        val characterName = character.name
        val world = character.world
        val level = try {
          character.level.toString.toDouble.toInt
        } catch {
          case _: Exception => 0
        }
        val vocation = character.vocation

        logger.info(s"Character found: $characterName (Level $level $vocation on $world)")

        // Formatuj nick: emoji + nazwa + [level]
        val professionEmoji = getProfessionEmoji(vocation)
        val formattedNickname = s"$professionEmoji $characterName [$level]"
        
        // Discord ma limit 32 znaków, więc sprawdź długość
        val finalNickname = if (formattedNickname.length > 32) {
          // Jeśli za długi, spróbuj bez emoji
          val withoutEmoji = s"$characterName [$level]"
          if (withoutEmoji.length > 32) {
            // Jeśli nadal za długi, tylko nazwa
            characterName.take(32)
          } else {
            withoutEmoji
          }
        } else {
          formattedNickname
        }

        logger.info(s"Setting nickname to: $finalNickname")

        // Zmień nick na Discordzie
        targetMember.modifyNickname(finalNickname).queue(
          _ => {
            val successEmbed = new EmbedBuilder()
              .setColor(Color.GREEN)
              .setTitle(s"${Config.yesEmoji} Nick został zmieniony!")
              .setDescription(
                s"**Członek:** ${targetMember.getAsMention}\n" +
                s"**Stary nick:** `$oldNick`\n" +
                s"**Nowy nick:** `$finalNickname`\n\n" +
                s"**Informacje o postaci:**\n" +
                s"${Config.levelUpEmoji} Level **$level** ${getProfessionEmoji(vocation)} $vocation\n" +
                s"🌍 Świat: **$world**"
              )
              .setThumbnail(Config.nameChangeThumbnail)
              .setFooter(s"Zmienione przez ${adminMember.getEffectiveName}", adminMember.getEffectiveAvatarUrl)
              .setTimestamp(ZonedDateTime.now())
              .build()

            event.getHook.editOriginalEmbeds(successEmbed).queue()

            logger.info(s"Successfully changed nickname for ${targetMember.getUser.getName} from '$oldNick' to '$finalNickname'")
          },
          error => {
            logger.error(s"Failed to change nickname: ${error.getMessage}")
            replyError(event, s"${Config.noEmoji} Nie mogę zmienić nicku! Sprawdź moje uprawnienia lub hierarchię ról.")
          }
        )

      case Success(Left(errorMessage)) =>
        logger.warn(s"TibiaData API error: $errorMessage")
        replyError(event, s"${Config.noEmoji} Postać **$newName** nie istnieje w Tibii!")

      case Failure(exception) =>
        logger.error(s"Failed to fetch character data: ${exception.getMessage}", exception)
        
        val errorMsg = exception.getMessage match {
          case msg if msg.contains("timeout") || msg.contains("Timeout") =>
            s"${Config.noEmoji} TibiaData API nie odpowiada (timeout).\n\n**Spróbuj ponownie za chwilę.**"
          case msg if msg.contains("Connection") || msg.contains("Tcp") =>
            s"${Config.noEmoji} Problem z połączeniem do TibiaData API.\n\n**Sprawdź później!**"
          case _ =>
            s"${Config.noEmoji} Błąd podczas sprawdzania postaci!\n\n**Szczegóły:** ${exception.getMessage}\n\n**Spróbuj ponownie.**"
        }
        
        replyError(event, errorMsg)
    }
  }

  /**
   * Pomocnicza metoda do wysyłania błędów
   */
  private def replyError(event: SlashCommandInteractionEvent, message: String): Unit = {
    event.getHook.editOriginal(message).queue()
  }

  /**
   * Zwraca emoji dla profesji
   */
  private def getProfessionEmoji(vocation: String): String = {
    vocation.toLowerCase match {
      case v if v.contains("knight") => "🛡️"
      case v if v.contains("paladin") => "🏹"
      case v if v.contains("sorcerer") => "🔥"
      case v if v.contains("druid") => "🌿"
      case v if v.contains("monk") => "👊"
      case _ => "⚔️"
    }
  }
}