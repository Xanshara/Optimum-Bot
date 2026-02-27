package com.tibiabot.events

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.{Commands, SubcommandData}
import net.dv8tion.jda.api.interactions.components.text.{TextInput, TextInputStyle}
import net.dv8tion.jda.api.interactions.modals.Modal
import net.dv8tion.jda.api.{Permission, JDA}
import com.typesafe.scalalogging.StrictLogging

import java.awt.Color
import java.sql.Timestamp
import java.text.SimpleDateFormat
import scala.util.{Try, Success, Failure}
import scala.jdk.CollectionConverters._
import scala.collection.concurrent.TrieMap

class EventCommand(
  jda: JDA,
  eventService: EventService,
  embedBuilder: EventEmbedBuilder,
  adminGuildId: Option[Long] = None
) extends StrictLogging {

  private val dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm")

  // Helper — obsługuje zwykłe kanały tekstowe i wątki forum
  private def getMessageChannel(channelId: Long): Option[net.dv8tion.jda.api.entities.channel.middleman.MessageChannel] = {
    Option(jda.getTextChannelById(channelId))
      .orElse(Option(jda.getThreadChannelById(channelId)))
      .map(_.asInstanceOf[net.dv8tion.jda.api.entities.channel.middleman.MessageChannel])
  }

  // Temporary storage dla multi-step creation i edit
  private val creationStates = new TrieMap[Long, EventCreationState]()

  // Temporary storage dla wyboru użytkownika do dodania
  private val selectedUsersToAdd = new TrieMap[String, List[Long]]()

  // ========== COMMAND REGISTRATION ==========

  def registerCommands(): Unit = {
    val eventCommand = Commands.slash("event", "Manage events")
      .addSubcommands(
        new SubcommandData("create", "Create a new event"),
        new SubcommandData("edit", "Edit event")
          .addOption(OptionType.INTEGER, "event_id", "Event ID", true),
        new SubcommandData("close", "Close event")
          .addOption(OptionType.INTEGER, "event_id", "Event ID", true),
        new SubcommandData("open", "Open event")
          .addOption(OptionType.INTEGER, "event_id", "Event ID", true),
        new SubcommandData("delete", "Delete event")
          .addOption(OptionType.INTEGER, "event_id", "Event ID", true),
        new SubcommandData("recurring", "Enable or disable recurring for an event")
          .addOption(OptionType.INTEGER, "event_id", "Event ID", true)
          .addOption(OptionType.STRING, "action", "on / off", true),
        new SubcommandData("list", "List active events"),
        new SubcommandData("servers", "List all guilds where bot is present (super admin only)"),
        new SubcommandData("clean", "Delete ALL events from a guild (super admin only)")
          .addOption(OptionType.STRING, "guild", "Guild ID or name", true)
      )

    jda.updateCommands().addCommands(eventCommand).queue()
  }

  // ========== COMMAND ROUTING ==========

  def handleCommand(event: SlashCommandInteractionEvent): Unit = {
    if (event.getName != "event") return

    event.getSubcommandName match {
      case "create" =>
        logger.info("Create command triggered - starting multi-step")
        openStep1Modal(event)

      case "edit" =>
        logger.info("Edit command triggered")
        handleEditCommand(event)

      case _ =>
        event.deferReply(true).queue()

        event.getSubcommandName match {
          case "list"      => handleList(event)
          case "servers"   => handleServers(event)
          case "open"      => handleOpen(event)
          case "close"     => handleClose(event)
          case "delete"    => handleDelete(event)
          case "recurring" => handleRecurring(event)
          case "clean"     => handleClean(event)
          case _ =>
            event.getHook.sendMessage("❌ Unknown subcommand").setEphemeral(true).queue()
        }
    }
  }

  // ========== MULTI-STEP CREATION ==========

  private def openStep1Modal(event: SlashCommandInteractionEvent): Unit = {
    if (!hasPermission(event)) {
      event.reply("❌ No permission").setEphemeral(true).queue()
      return
    }

    val modal = Modal.create("event_create_step1", "Create Event - Step 1/3")
      .addActionRow(
        TextInput.create("title", "Title", TextInputStyle.SHORT)
          .setPlaceholder("Event name (e.g. Ferumbras Raid)")
          .setRequired(true).build()
      )
      .addActionRow(
        TextInput.create("description", "Description", TextInputStyle.PARAGRAPH)
          .setPlaceholder("Optional event description")
          .setRequired(false).build()
      )
      .build()

    event.replyModal(modal).queue()
  }

  def openStep2Modal(userId: Long, channelId: Long, messageId: Long): Unit = {
    val channel = getMessageChannel(channelId).orNull
    if (channel == null) return

    channel.retrieveMessageById(messageId).queue { msg =>
      val modal = Modal.create("event_create_step2", "Create Event - Step 2/3")
        .addActionRow(
          TextInput.create("date", "Date (YYYY-MM-DD)", TextInputStyle.SHORT)
            .setPlaceholder("e.g. 2025-01-15")
            .setRequired(true).build()
        )
        .addActionRow(
          TextInput.create("time", "Time (HH:MM)", TextInputStyle.SHORT)
            .setPlaceholder("e.g. 20:00")
            .setRequired(true).build()
        )
        .addActionRow(
          TextInput.create("reminder", "Reminder (minutes before event)", TextInputStyle.SHORT)
            .setPlaceholder("e.g. 15")
            .setValue("15")
            .setRequired(true).build()
        )
        .build()
      // handled by EventButtonListener
    }
  }

  def openStep3Modal(userId: Long, channelId: Long, messageId: Long): Unit = {
    // handled by EventButtonListener
  }

  def handleModalStep1(event: ModalInteractionEvent): Unit = {
    val title = event.getValue("title").getAsString
    val description = Option(event.getValue("description")).map(_.getAsString).filter(_.nonEmpty)

    val userId = event.getUser.getIdLong
    val guildId = event.getGuild.getIdLong
    val channelId = event.getChannel.getIdLong

    val state = EventCreationState(
      userId = userId,
      guildId = guildId,
      channelId = channelId,
      title = Some(title),
      description = description,
      currentStep = 1
    )
    creationStates.put(userId, state)

    event.deferReply(false).queue()

    val embed = new net.dv8tion.jda.api.EmbedBuilder()
      .setTitle("📋 Create Event - Step 1.5/3")
      .setDescription(s"**Title:** $title\n${description.map(d => s"**Description:** $d\n").getOrElse("")}\nSelect a role to mention when the event is posted:")
      .setColor(Color.BLUE)
      .build()

    val guild = event.getGuild
    val roles = guild.getRoles.asScala.toList
      .filter(_.getName != "@everyone")
      .sortBy(_.getPosition)(Ordering[Int].reverse)
      .take(23)

    val dropdown = net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu.create(s"event:select_role:$userId")
      .setPlaceholder("Select role to mention")
      .addOption("@everyone", "everyone", "Mention everyone")
      .addOption("@here", "here", "Mention online users")
      .addOptions(roles.map { role =>
        net.dv8tion.jda.api.interactions.components.selections.SelectOption.of(role.getName, role.getId)
      }.asJava)
      .build()

    val continueButton = net.dv8tion.jda.api.interactions.components.buttons.Button.primary(
      s"event:continue_step2:$userId",
      "Continue to Date & Time →"
    )

    event.getHook.sendMessageEmbeds(embed)
      .addActionRow(dropdown)
      .addActionRow(continueButton)
      .queue { msg =>
        creationStates.get(userId).foreach { s =>
          creationStates.put(userId, s.copy(lastInteractionMessageId = Some(msg.getIdLong)))
        }
      }
  }

  def handleModalStep2(event: ModalInteractionEvent): Unit = {
    val userId = event.getUser.getIdLong
    event.deferReply(true).queue()

    creationStates.get(userId) match {
      case None =>
        event.getHook.sendMessage("❌ Session expired. Please start over with `/event create`").setEphemeral(true).queue()
        return

      case Some(state) =>
        val dateStr = event.getValue("date").getAsString
        val timeStr = event.getValue("time").getAsString
        val reminderStr = event.getValue("reminder").getAsString

        val dateTimeStr = s"$dateStr $timeStr"
        val eventTime = Try(new Timestamp(dateFormat.parse(dateTimeStr).getTime)).toOption
        val reminderMinutes = Try(reminderStr.toInt).toOption

        if (eventTime.isEmpty) {
          event.getHook.sendMessage("❌ Invalid date/time format. Use YYYY-MM-DD for date and HH:MM for time").setEphemeral(true).queue()
          return
        }

        if (reminderMinutes.isEmpty || reminderMinutes.get < 0) {
          event.getHook.sendMessage("❌ Invalid reminder minutes. Must be a positive number").setEphemeral(true).queue()
          return
        }

        val updatedState = state.copy(
          eventTime = eventTime,
          reminderMinutes = reminderMinutes,
          currentStep = 2
        )
        creationStates.put(userId, updatedState)

        val embed = new net.dv8tion.jda.api.EmbedBuilder()
          .setTitle("📋 Create Event - Step 3/3")
          .setDescription(s"**Title:** ${state.title.get}\n**Date & Time:** $dateTimeStr\n**Reminder:** ${reminderMinutes.get} minutes before\n\nClick button below to set participant slots:")
          .setColor(Color.BLUE)
          .build()

        val continueButton = net.dv8tion.jda.api.interactions.components.buttons.Button.primary(
          s"event:continue_step3:$userId",
          "Set Slots →"
        )

        event.getHook.sendMessageEmbeds(embed)
          .addActionRow(continueButton)
          .queue { msg =>
            creationStates.get(userId).foreach { s =>
              creationStates.put(userId, s.copy(lastInteractionMessageId = Some(msg.getIdLong)))
            }
          }
    }
  }

  def handleModalStep3(event: ModalInteractionEvent): Unit = {
    val userId = event.getUser.getIdLong
    event.deferReply(true).queue()

    creationStates.get(userId) match {
      case None =>
        event.getHook.sendMessage("❌ Session expired. Please start over with `/event create`").setEphemeral(true).queue()
        return

      case Some(state) =>
        val tankStr  = event.getValue("tank").getAsString
        val healerStr = event.getValue("healer").getAsString
        val dpsStr   = event.getValue("dps").getAsString

        val tank   = Try(tankStr.toInt).toOption
        val healer = Try(healerStr.toInt).toOption
        val dps    = Try(dpsStr.toInt).toOption

        if (tank.isEmpty || healer.isEmpty || dps.isEmpty) {
          event.getHook.sendMessage("❌ Invalid slot numbers. Must be positive integers").setEphemeral(true).queue()
          return
        }

        if (tank.get < 0 || healer.get < 0 || dps.get < 0) {
          event.getHook.sendMessage("❌ Slot numbers must be 0 or greater").setEphemeral(true).queue()
          return
        }

        val updatedState = state.copy(tankLimit = tank, healerLimit = healer, dpsLimit = dps)
        creationStates.put(userId, updatedState)

        // Zapytaj o cykliczność
        val embed = new net.dv8tion.jda.api.EmbedBuilder()
          .setTitle("📋 Create Event - Ostatni krok")
          .setDescription(
            s"**Sloty:** 🛡 ${tank.get} / 💚 ${healer.get} / ⚔ ${dps.get}\n\n" +
            "Czy ten event ma się **powtarzać cyklicznie**?"
          )
          .setColor(java.awt.Color.BLUE)
          .build()

        val yesButton = net.dv8tion.jda.api.interactions.components.buttons.Button
          .primary(s"event:recurring_yes:$userId", "🔁 Tak, cykliczny")
        val noButton = net.dv8tion.jda.api.interactions.components.buttons.Button
          .secondary(s"event:recurring_no:$userId", "✅ Nie, jednorazowy")

        event.getHook.sendMessageEmbeds(embed)
          .addActionRow(yesButton, noButton)
          .setEphemeral(true)
          .queue()
    }
  }

  // ========== CREATION STATE HELPERS ==========

  def getCreationState(userId: Long): Option[EventCreationState] = creationStates.get(userId)

  def updateCreationState(userId: Long, state: EventCreationState): Unit = creationStates.put(userId, state)

  def removeCreationState(userId: Long): Unit = creationStates.remove(userId)

  /**
   * Tworzy event po wyborze cykliczności.
   * Wywoływane z EventButtonListener po kliknięciu "jednorazowy" lub wybraniu interwału.
   */
  def finalizeEventCreation(
    userId: Long,
    isRecurring: Boolean,
    recurringIntervalDays: Option[Int],
    hook: net.dv8tion.jda.api.interactions.InteractionHook
  ): Unit = {
    creationStates.get(userId) match {
      case None =>
        hook.sendMessage("❌ Session expired. Please start over with `/event create`").setEphemeral(true).queue()

      case Some(state) =>
        val finalState = state.copy(
          isRecurring = Some(isRecurring),
          recurringIntervalDays = recurringIntervalDays
        )

        if (!finalState.isComplete) {
          hook.sendMessage("❌ Missing data. Please start over with `/event create`").setEphemeral(true).queue()
          creationStates.remove(userId)
          return
        }

        val channel = getMessageChannel(finalState.channelId).orNull
        if (channel == null) {
          hook.sendMessage("❌ Channel not found").setEphemeral(true).queue()
          creationStates.remove(userId)
          return
        }

        val mentionText = finalState.mentionRoleId match {
          case Some(-1L)    => "@everyone"
          case Some(-2L)    => "@here"
          case Some(roleId) => s"<@&$roleId>"
          case None         => "\u200B"
        }

        channel.sendMessage(mentionText).queue { msg =>
          val newEvent = finalState.toEvent(msg.getIdLong, userId)

          eventService.createEvent(newEvent) match {
            case Success(createdEvent) =>
              val signups = eventService.getSignupsByRole(createdEvent.id)
              val embed   = embedBuilder.buildEventEmbed(createdEvent, signups)
              val buttons = EventButtons.createEventButtons(createdEvent.id, createdEvent.active)

              msg.editMessage(mentionText)
                .setEmbeds(embed)
                .setComponents(buttons.asJava)
                .queue()

              val recurringInfo = if (isRecurring) {
                val days = recurringIntervalDays.getOrElse(7)
                s" (🔁 co $days dni)"
              } else ""

              hook.sendMessage(s"✅ Event **${createdEvent.title}** created!$recurringInfo (ID: ${createdEvent.id})")
                .setEphemeral(true).queue()
              creationStates.remove(userId)

            case Failure(e) =>
              msg.delete().queue()
              hook.sendMessage(s"❌ ${e.getMessage}").setEphemeral(true).queue()
              creationStates.remove(userId)
          }
        }
    }
  }

  // ========== SLASH COMMAND EDIT (otwiera Step 1) ==========

  private def handleEditCommand(event: SlashCommandInteractionEvent): Unit = {
    if (!hasPermission(event)) {
      event.reply("❌ No permission").setEphemeral(true).queue()
      return
    }

    val eventId = event.getOption("event_id").getAsInt

    eventService.getEvent(eventId) match {
      case None =>
        event.reply("❌ Event not found").setEphemeral(true).queue()

      case Some(existingEvent) =>
        val modal = Modal.create(s"event_edit_step1:$eventId", "Edit Event - Step 1/2")
          .addActionRow(
            TextInput.create("title", "Title", TextInputStyle.SHORT)
              .setValue(existingEvent.title)
              .setRequired(true).build()
          )
          .addActionRow(
            TextInput.create("description", "Description", TextInputStyle.PARAGRAPH)
              .setValue(existingEvent.description.getOrElse(""))
              .setPlaceholder("Leave empty to remove description")
              .setRequired(false)
              .setMaxLength(1000)
              .build()
          )
          .addActionRow(
            TextInput.create("datetime", "Date & Time (YYYY-MM-DD HH:MM)", TextInputStyle.SHORT)
              .setValue(dateFormat.format(existingEvent.eventTime))
              .setRequired(false).build()
          )
          .build()

        event.replyModal(modal).queue()
    }
  }

  // ========== MULTI-STEP EDIT ==========

  /**
   * Step 1 submit — title + description + datetime
   * Modal ID: event_edit_step1:{eventId}
   */
  def handleModalEditStep1(event: ModalInteractionEvent, eventId: Int): Unit = {
    val userId = event.getUser.getIdLong

    eventService.getEvent(eventId) match {
      case None =>
        event.reply("❌ Event not found").setEphemeral(true).queue()

      case Some(existing) =>
        val title = Option(event.getValue("title"))
          .map(_.getAsString.trim).filter(_.nonEmpty).getOrElse(existing.title)

        val description = Option(event.getValue("description"))
          .map(_.getAsString.trim).filter(_.nonEmpty)

        val dateTimeStr = Option(event.getValue("datetime"))
          .map(_.getAsString.trim).filter(_.nonEmpty)

        val eventTime = dateTimeStr match {
          case Some(dt) => Try(new Timestamp(dateFormat.parse(dt).getTime)).getOrElse(existing.eventTime)
          case None     => existing.eventTime
        }

        // Zapisz stan edycji
        val editState = EventEditState(
          eventId     = eventId,
          title       = title,
          description = description,
          eventTime   = eventTime
        )

        val creationState = EventCreationState(
          userId      = userId,
          guildId     = event.getGuild.getIdLong,
          channelId   = event.getChannel.getIdLong,
          pendingEdit = Some(editState)
        )
        creationStates.put(userId, creationState)

        event.deferReply(true).queue()

        val descLine = description match {
          case Some(d) => s"**Description:** $d"
          case None    => "**Description:** *(empty)*"
        }

        val embed = new net.dv8tion.jda.api.EmbedBuilder()
          .setTitle("✏️ Edit Event — Step 1/2 complete")
          .setDescription(
            s"**Title:** $title\n" +
            s"$descLine\n" +
            s"**Date/Time:** ${dateFormat.format(eventTime)}\n\n" +
            "Click **Next** to edit slot limits (Tank / Healer / DPS)."
          )
          .setColor(java.awt.Color.ORANGE)
          .build()

        val nextButton = net.dv8tion.jda.api.interactions.components.buttons.Button
          .primary(s"event:edit_step2:$userId", "Next → Edit Slots")

        event.getHook.sendMessageEmbeds(embed)
          .addActionRow(nextButton)
          .setEphemeral(true)
          .queue()
    }
  }

  /**
   * Otwiera modal Step 2 edycji — wywoływany przez przycisk "Next → Edit Slots"
   * Modal ID: event_edit_step2:{eventId}
   */
  def openEditStep2Modal(event: ButtonInteractionEvent, userId: Long): Unit = {
    creationStates.get(userId) match {
      case None =>
        event.reply("❌ Session expired. Please click Edit again.").setEphemeral(true).queue()

      case Some(state) =>
        state.pendingEdit match {
          case None =>
            event.reply("❌ Session expired. Please click Edit again.").setEphemeral(true).queue()

          case Some(pendingEdit) =>
            val existing = eventService.getEvent(pendingEdit.eventId)
            val (tank, healer, dps) = existing
              .map(e => (e.tankLimit, e.healerLimit, e.dpsLimit))
              .getOrElse((1, 1, 1))

            val modal = Modal.create(s"event_edit_step2:${pendingEdit.eventId}", "Edit Event - Step 2/2")
              .addActionRow(
                TextInput.create("tank", "🛡 Tank slots", TextInputStyle.SHORT)
                  .setValue(tank.toString)
                  .setRequired(true).build()
              )
              .addActionRow(
                TextInput.create("healer", "💚 Healer slots", TextInputStyle.SHORT)
                  .setValue(healer.toString)
                  .setRequired(true).build()
              )
              .addActionRow(
                TextInput.create("dps", "⚔ DPS slots", TextInputStyle.SHORT)
                  .setValue(dps.toString)
                  .setRequired(true).build()
              )
              .build()

            event.replyModal(modal).queue()
        }
    }
  }

  /**
   * Step 2 submit — tank + healer + dps
   * Modal ID: event_edit_step2:{eventId}
   */
  def handleModalEditStep2(event: ModalInteractionEvent, eventId: Int): Unit = {
    event.deferReply(true).queue()

    val userId = event.getUser.getIdLong

    creationStates.get(userId).flatMap(_.pendingEdit) match {
      case None =>
        event.getHook.sendMessage("❌ Session expired. Please click Edit again.").setEphemeral(true).queue()

      case Some(pendingEdit) =>
        eventService.getEvent(eventId) match {
          case None =>
            event.getHook.sendMessage("❌ Event not found").setEphemeral(true).queue()

          case Some(existing) =>
            val tankLimit   = Try(event.getValue("tank").getAsString.trim.toInt).filter(_ >= 0).getOrElse(existing.tankLimit)
            val healerLimit = Try(event.getValue("healer").getAsString.trim.toInt).filter(_ >= 0).getOrElse(existing.healerLimit)
            val dpsLimit    = Try(event.getValue("dps").getAsString.trim.toInt).filter(_ >= 0).getOrElse(existing.dpsLimit)

            val updated = existing.copy(
              title       = pendingEdit.title,
              description = pendingEdit.description,
              eventTime   = pendingEdit.eventTime,
              tankLimit   = tankLimit,
              healerLimit = healerLimit,
              dpsLimit    = dpsLimit
            )

            eventService.updateEvent(updated) match {
              case Success(_) =>
                updateEventMessage(updated)
                creationStates.remove(userId)
                event.getHook.sendMessage("✅ Event updated successfully!").setEphemeral(true).queue()

              case Failure(e) =>
                event.getHook.sendMessage(s"❌ ${e.getMessage}").setEphemeral(true).queue()
            }
        }
    }
  }

  // ========== MANAGE MODAL HANDLERS ==========

  def handleModalManage(event: ModalInteractionEvent, eventId: Int): Unit = {
    event.deferReply(true).queue()

    val existingOpt = eventService.getEvent(eventId)
    if (existingOpt.isEmpty) {
      event.getHook.sendMessage("❌ Event not found").setEphemeral(true).queue()
      return
    }

    val existing = existingOpt.get

    val removeUsersStr = Option(event.getValue("remove_users")).map(_.getAsString).filter(_.nonEmpty)
    val addUserStr = Option(event.getValue("add_user")).map(_.getAsString).filter(_.nonEmpty)
    val addRoleStr = Option(event.getValue("add_role")).map(_.getAsString).filter(_.nonEmpty)

    var results = List.empty[String]
    var errors = List.empty[String]
    var updated = false

    removeUsersStr.foreach { usersStr =>
      val userIds = usersStr.split(",").map(_.trim).filter(_.nonEmpty)
      userIds.foreach { userIdStr =>
        Try(userIdStr.toLong).toOption match {
          case Some(userId) =>
            eventService.unsignupUser(eventId, userId) match {
              case Success(_) =>
                results = results :+ s"✅ Removed <@$userId>"
                updated = true
              case Failure(e) =>
                errors = errors :+ s"❌ Failed to remove <@$userId>: ${e.getMessage}"
            }
          case None =>
            errors = errors :+ s"❌ Invalid user ID: `$userIdStr`"
        }
      }
    }

    for {
      userIdStr <- addUserStr
      roleStr   <- addRoleStr
      userId    <- Try(userIdStr.toLong).toOption
      role      <- EventRole.fromString(roleStr.toLowerCase.trim)
    } {
      eventService.signupUser(eventId, userId, role) match {
        case Success(assignedRole) =>
          results = results :+ s"✅ Added <@$userId> as **${assignedRole.name}**"
          updated = true
        case Failure(e) =>
          errors = errors :+ s"❌ Failed to add <@$userId>: ${e.getMessage}"
      }
    }

    if (updated) updateEventMessage(existing)

    val response = new StringBuilder
    if (results.nonEmpty) response.append(results.mkString("\n"))
    if (errors.nonEmpty) response.append("\n" + errors.mkString("\n"))
    if (response.isEmpty) response.append("❌ No changes made. Please specify users to remove or add.")

    event.getHook.sendMessage(response.toString).setEphemeral(true).queue()
  }

  def handleModalManageAdd(event: ModalInteractionEvent, eventId: Int): Unit = {
    event.deferReply(true).queue()

    val existingOpt = eventService.getEvent(eventId)
    if (existingOpt.isEmpty) {
      event.getHook.sendMessage("❌ Event not found").setEphemeral(true).queue()
      return
    }

    val existing = existingOpt.get

    val addUserStr = Option(event.getValue("add_user")).map(_.getAsString).filter(_.nonEmpty)
    val addRoleStr = Option(event.getValue("add_role")).map(_.getAsString).filter(_.nonEmpty)

    if (addUserStr.isEmpty || addRoleStr.isEmpty) {
      event.getHook.sendMessage("❌ Both User ID and Role are required").setEphemeral(true).queue()
      return
    }

    val userIdStr = addUserStr.get
    val roleStr = addRoleStr.get.toLowerCase.trim

    (Try(userIdStr.toLong).toOption, EventRole.fromString(roleStr)) match {
      case (Some(userId), Some(role)) =>
        eventService.signupUser(eventId, userId, role) match {
          case Success(assignedRole) =>
            val message = if (assignedRole == role)
              s"✅ Added <@$userId> as **${role.name}**"
            else
              s"✅ Added <@$userId> to **${assignedRole.name}** (${role.name} was full)"
            updateEventMessage(existing)
            event.getHook.sendMessage(message).setEphemeral(true).queue()

          case Failure(e) if e.getMessage == "UNREGISTER" =>
            event.getHook.sendMessage(s"❌ User <@$userId> is already signed up for this role").setEphemeral(true).queue()

          case Failure(e) =>
            event.getHook.sendMessage(s"❌ Failed to add <@$userId>: ${e.getMessage}").setEphemeral(true).queue()
        }

      case (None, _) =>
        event.getHook.sendMessage(s"❌ Invalid user ID: `$userIdStr`").setEphemeral(true).queue()

      case (_, None) =>
        event.getHook.sendMessage(s"❌ Invalid role: `$roleStr` (use: tank, healer, dps, or waitlist)").setEphemeral(true).queue()
    }
  }

  // ========== RECURRING TOGGLE ==========

  private def handleRecurring(event: SlashCommandInteractionEvent): Unit = {
    if (!hasPermission(event)) {
      event.getHook.sendMessage("❌ No permission").setEphemeral(true).queue()
      return
    }

    val eventId = event.getOption("event_id").getAsInt
    val action  = event.getOption("action").getAsString.trim.toLowerCase

    if (action != "on" && action != "off") {
      event.getHook.sendMessage("❌ Invalid action. Use `on` or `off`").setEphemeral(true).queue()
      return
    }

    eventService.getEvent(eventId) match {
      case None =>
        event.getHook.sendMessage(s"❌ Event $eventId not found").setEphemeral(true).queue()

      case Some(existing) =>
        if (action == "off") {
          val updated = existing.copy(isRecurring = false, recurringIntervalDays = None)
          eventService.updateEvent(updated) match {
            case Success(_) =>
              updateEventMessage(updated)
              event.getHook.sendMessage(s"✅ Event **${existing.title}** (ID: $eventId) — cykliczność **wyłączona**").setEphemeral(true).queue()
            case Failure(e) =>
              event.getHook.sendMessage(s"❌ ${e.getMessage}").setEphemeral(true).queue()
          }
        } else {
          // action == "on" — sprawdź czy event już ma interval
          existing.recurringIntervalDays match {
            case Some(days) =>
              // Ma już interwał — po prostu włącz z powrotem
              val updated = existing.copy(isRecurring = true, nextEventCreated = false)
              eventService.updateEvent(updated) match {
                case Success(_) =>
                  updateEventMessage(updated)
                  event.getHook.sendMessage(s"✅ Event **${existing.title}** (ID: $eventId) — cykliczność **włączona** (co $days dni)").setEphemeral(true).queue()
                case Failure(e) =>
                  event.getHook.sendMessage(s"❌ ${e.getMessage}").setEphemeral(true).queue()
              }
            case None =>
              // Nigdy nie był cykliczny — poproś o interwał przez dropdown
              val dropdown = net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu
                .create(s"event:set_recurring_interval:$eventId")
                .setPlaceholder("Wybierz co ile dni event ma się powtarzać")
                .addOption("Co 1 dzień",  "1",  "Codziennie")
                .addOption("Co 2 dni",    "2",  "Co dwa dni")
                .addOption("Co 7 dni",    "7",  "Tygodniowo")
                .addOption("Co 14 dni",   "14", "Co dwa tygodnie")
                .addOption("Co 30 dni",   "30", "Miesięcznie")
                .build()

              event.getHook.sendMessage(s"📅 Wybierz interwał dla eventu **${existing.title}**:")
                .addActionRow(dropdown)
                .setEphemeral(true)
                .queue()
          }
        }
    }
  }

  // ========== SIMPLE COMMANDS ==========

  private def handleList(event: SlashCommandInteractionEvent): Unit = {
    val events = eventService.getActiveEvents(event.getGuild.getIdLong)

    if (events.isEmpty) {
      event.getHook.sendMessage("❌ No active events").setEphemeral(true).queue()
      return
    }

    val embed = new net.dv8tion.jda.api.EmbedBuilder()
      .setTitle("📋 Active Events")
      .setColor(Color.GREEN)

    events.foreach { e =>
      embed.addField(
        s"#${e.id} ${e.title}",
        dateFormat.format(e.eventTime),
        false
      )
    }

    event.getHook.sendMessageEmbeds(embed.build()).queue()
  }

  private def handleServers(event: SlashCommandInteractionEvent): Unit = {
    val currentGuildId = event.getGuild.getIdLong

    adminGuildId match {
      case None =>
        event.getHook.sendMessage("❌ Admin guild not configured").setEphemeral(true).queue()

      case Some(adminId) if currentGuildId != adminId =>
        event.getHook.sendMessage("❌ This command can only be used on the admin guild").setEphemeral(true).queue()

      case Some(_) =>
        val guilds = jda.getGuilds.asScala.toList.sortBy(_.getName.toLowerCase)

        if (guilds.isEmpty) {
          event.getHook.sendMessage("❌ Bot is not in any guilds").setEphemeral(true).queue()
          return
        }

        val embed = new net.dv8tion.jda.api.EmbedBuilder()
          .setTitle("🌐 Bot Guilds")
          .setDescription(s"Bot is present in **${guilds.size}** guilds:")
          .setColor(Color.BLUE)

        guilds.take(25).foreach { guild =>
          val eventCount = eventService.getActiveEvents(guild.getIdLong).size
          embed.addField(
            guild.getName,
            s"ID: `${guild.getIdLong}`\nActive events: $eventCount",
            true
          )
        }

        if (guilds.size > 25) embed.setFooter(s"Showing first 25 of ${guilds.size} guilds")

        event.getHook.sendMessageEmbeds(embed.build()).setEphemeral(true).queue()
    }
  }

  private def handleClose(event: SlashCommandInteractionEvent): Unit =
    simpleEventAction(event, eventService.closeEvent, "closed")

  private def handleOpen(event: SlashCommandInteractionEvent): Unit =
    simpleEventAction(event, eventService.openEvent, "opened")

  private def handleDelete(event: SlashCommandInteractionEvent): Unit =
    simpleEventAction(event, eventService.deleteEvent, "deleted")

  private def handleClean(event: SlashCommandInteractionEvent): Unit = {
    val currentGuildId = event.getGuild.getIdLong

    adminGuildId match {
      case None =>
        event.getHook.sendMessage("❌ Admin guild not configured").setEphemeral(true).queue()

      case Some(adminId) if currentGuildId != adminId =>
        event.getHook.sendMessage("❌ This command can only be used on the admin guild").setEphemeral(true).queue()

      case Some(_) =>
        val identifier = event.getOption("guild").getAsString
        findGuildByIdentifier(identifier) match {
          case None =>
            event.getHook.sendMessage(s"❌ Guild not found: `$identifier`").setEphemeral(true).queue()

          case Some(targetGuild) =>
            eventService.deleteEventsByGuild(targetGuild.getIdLong) match {
              case Success(count) =>
                event.getHook.sendMessage(s"✅ Deleted **$count** event(s) from **${targetGuild.getName}**").setEphemeral(true).queue()

              case Failure(e) =>
                event.getHook.sendMessage(s"❌ ${e.getMessage}").setEphemeral(true).queue()
            }
        }
    }
  }

  // ========== HELPERS ==========

  def hasPermission(event: SlashCommandInteractionEvent): Boolean =
    event.getMember != null &&
      (event.getMember.hasPermission(Permission.MANAGE_CHANNEL) ||
        event.getMember.hasPermission(Permission.ADMINISTRATOR))

  def getSelectedUserToAdd(key: String): Option[List[Long]] = selectedUsersToAdd.get(key)

  def setSelectedUserToAdd(key: String, userIds: List[Long]): Unit = selectedUsersToAdd.put(key, userIds)

  def clearSelectedUserToAdd(key: String): Unit = selectedUsersToAdd.remove(key)

  def addUserToEvent(eventId: Int, userId: Long, role: EventRole): Try[EventRole] = {
    eventService.getEvent(eventId) match {
      case None =>
        Failure(new Exception(s"Event $eventId not found"))

      case Some(event) =>
        eventService.signupUser(eventId, userId, role) match {
          case Success(assignedRole) =>
            updateEventMessage(event)
            Success(assignedRole)

          case failure => failure
        }
    }
  }

  private def findGuildByIdentifier(identifier: String): Option[net.dv8tion.jda.api.entities.Guild] = {
    val byId = Try(identifier.toLong).toOption.flatMap(id => Option(jda.getGuildById(id)))
    if (byId.isDefined) return byId
    jda.getGuilds.asScala.toList.find(_.getName.equalsIgnoreCase(identifier))
  }

  private def simpleEventAction(
    event: SlashCommandInteractionEvent,
    action: Int => Try[Unit],
    label: String
  ): Unit = {
    if (!hasPermission(event)) {
      event.getHook.sendMessage("❌ No permission").setEphemeral(true).queue()
      return
    }

    val idOpt = Option(event.getOption("event_id")).map(_.getAsInt)
    if (idOpt.isEmpty) {
      event.getHook.sendMessage("❌ Missing event_id").setEphemeral(true).queue()
      return
    }

    action(idOpt.get) match {
      case Success(_) =>
        eventService.getEvent(idOpt.get).foreach(e => updateEventMessage(e))
        event.getHook.sendMessage(s"✅ Event $label").setEphemeral(true).queue()
      case Failure(e) =>
        event.getHook.sendMessage(s"❌ ${e.getMessage}").setEphemeral(true).queue()
    }
  }

  def updateEventMessage(event: Event, prependText: String = ""): Unit = {
    val channel = getMessageChannel(event.channelId).orNull
    if (channel == null) return

    channel.retrieveMessageById(event.messageId).queue { msg =>
      val signups = eventService.getSignupsByRole(event.id)
      val embed = embedBuilder.buildEventEmbed(event, signups)
      val buttons = EventButtons.createEventButtons(event.id, event.active)

      val editAction = if (prependText.nonEmpty)
        msg.editMessage(prependText).setEmbeds(embed)
      else
        msg.editMessageEmbeds(embed)

      editAction.setComponents(buttons.asJava).queue()
    }
  }
}
