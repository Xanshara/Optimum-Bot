package com.tibiabot.events

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
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
  adminGuildId: Option[Long] = None  // Admin Guild ID dla /event clean i /event servers
) extends StrictLogging {

  private val dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm")
  
  // Temporary storage dla multi-step creation
  private val creationStates = new TrieMap[Long, EventCreationState]()
  
  // Temporary storage dla wyboru użytkownika do dodania
  private val selectedUsersToAdd = new TrieMap[String, List[Long]]()

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
        new SubcommandData("list", "List active events"),
        new SubcommandData("servers", "List all guilds where bot is present (super admin only)"),
        new SubcommandData("clean", "Delete ALL events from a guild (super admin only)")
          .addOption(OptionType.STRING, "guild", "Guild ID or name", true)  // true = REQUIRED!
      )

    jda.updateCommands().addCommands(eventCommand).queue()
  }

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
          case "list"    => handleList(event)
          case "servers" => handleServers(event)
          case "open"    => handleOpen(event)
          case "close"   => handleClose(event)
          case "delete"  => handleDelete(event)
          case "clean"   => handleClean(event)
          case _ =>
            event.getHook.sendMessage("❌ Unknown subcommand").setEphemeral(true).queue()
        }
    }
  }

  // ========== MULTI-STEP CREATION ==========

  /**
   * Step 1: Title + Description
   */
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
  
  /**
   * Step 2: Date + Time + Reminder
   */
  def openStep2Modal(userId: Long, channelId: Long, messageId: Long): Unit = {
    val channel = jda.getTextChannelById(channelId)
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
      
      // We can't open modal from button - need to edit message with instruction
      // This is handled by EventButtonListener
    }
  }
  
  /**
   * Step 3: Tank + Healer + DPS
   */
  def openStep3Modal(userId: Long, channelId: Long, messageId: Long): Unit = {
    // Same as step 2 - handled by EventButtonListener
  }

  /**
   * Handle Step 1 Modal Submit
   */
  def handleModalStep1(event: ModalInteractionEvent): Unit = {
    val title = event.getValue("title").getAsString
    val description = Option(event.getValue("description")).map(_.getAsString).filter(_.nonEmpty)
    
    val userId = event.getUser.getIdLong
    val guildId = event.getGuild.getIdLong
    val channelId = event.getChannel.getIdLong
    
    // Zapisz stan
    val state = EventCreationState(
      userId = userId,
      guildId = guildId,
      channelId = channelId,
      title = Some(title),
      description = description,
      currentStep = 1
    )
    creationStates.put(userId, state)
    
    // Wyślij message z dropdown do wyboru roli
    event.deferReply(false).queue()
    
    val embed = new net.dv8tion.jda.api.EmbedBuilder()
      .setTitle("📋 Create Event - Step 1.5/3")
      .setDescription(s"**Title:** $title\n${description.map(d => s"**Description:** $d\n").getOrElse("")}\nSelect a role to mention when the event is posted:")
      .setColor(Color.BLUE)
      .build()
    
    // Get all roles from guild
    val guild = event.getGuild
    val roles = guild.getRoles.asScala.toList
      .filter(_.getName != "@everyone")  // Exclude @everyone from dropdown
      .sortBy(_.getPosition)(Ordering[Int].reverse)
      .take(23)  // Discord dropdown limit (25 total - 2 for @everyone and @here)
    
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
        // Update state with message ID
        creationStates.get(userId).foreach { s =>
          creationStates.put(userId, s.copy(lastInteractionMessageId = Some(msg.getIdLong)))
        }
      }
  }
  
  /**
   * Handle Step 2 Modal Submit
   */
  def handleModalStep2(event: ModalInteractionEvent): Unit = {
    val userId = event.getUser.getIdLong
    
    // Acknowledge interaction immediately to prevent timeout
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
        
        // Update state
        val updatedState = state.copy(
          eventTime = eventTime,
          reminderMinutes = reminderMinutes,
          currentStep = 2
        )
        creationStates.put(userId, updatedState)
        
        // Show Step 3 prompt
        
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
  
  /**
   * Handle Step 3 Modal Submit
   */
  def handleModalStep3(event: ModalInteractionEvent): Unit = {
    val userId = event.getUser.getIdLong
    
    // Acknowledge interaction immediately to prevent timeout
    event.deferReply(true).queue()
    
    creationStates.get(userId) match {
      case None =>
        event.getHook.sendMessage("❌ Session expired. Please start over with `/event create`").setEphemeral(true).queue()
        return
        
      case Some(state) =>
        val tankStr = event.getValue("tank").getAsString
        val healerStr = event.getValue("healer").getAsString
        val dpsStr = event.getValue("dps").getAsString
        
        val tank = Try(tankStr.toInt).toOption
        val healer = Try(healerStr.toInt).toOption
        val dps = Try(dpsStr.toInt).toOption
        
        if (tank.isEmpty || healer.isEmpty || dps.isEmpty) {
          event.getHook.sendMessage("❌ Invalid slot numbers. Must be positive integers").setEphemeral(true).queue()
          return
        }
        
        if (tank.get < 0 || healer.get < 0 || dps.get < 0) {
          event.getHook.sendMessage("❌ Slot numbers must be 0 or greater").setEphemeral(true).queue()
          return
        }
        
        // Update state - now complete!
        val finalState = state.copy(
          tankLimit = tank,
          healerLimit = healer,
          dpsLimit = dps,
          currentStep = 3
        )
        
        if (!finalState.isComplete) {
          event.getHook.sendMessage("❌ Missing data. Please start over with `/event create`").setEphemeral(true).queue()
          creationStates.remove(userId)
          return
        }
        
        // CREATE THE EVENT!
        
        val channel = event.getChannel.asGuildMessageChannel()
        channel.sendMessage("⏳ Creating event...").queue { msg =>
          val newEvent = finalState.toEvent(
            messageId = msg.getIdLong,
            createdBy = userId
          )
          
          eventService.createEvent(newEvent) match {
            case Success(evt) =>
              // Send mention if role selected
              val mentionText = evt.mentionRoleId match {
                case Some(roleId) if roleId == -1 => "@everyone" // Special case
                case Some(roleId) if roleId == -2 => "@here"     // Special case
                case Some(roleId) => s"<@&$roleId>"
                case None => ""
              }
              
              // Update message with event embed
              updateEventMessage(evt, mentionText)
              event.getHook.sendMessage(s"✅ Event created (ID ${evt.id})!").setEphemeral(true).queue()
              
              // Cleanup
              creationStates.remove(userId)
              
            case Failure(e) =>
              msg.delete().queue()
              event.getHook.sendMessage(s"❌ ${e.getMessage}").setEphemeral(true).queue()
              creationStates.remove(userId)
          }
        }
    }
  }
  
  /**
   * Get creation state for user
   */
  def getCreationState(userId: Long): Option[EventCreationState] = {
    creationStates.get(userId)
  }
  
  /**
   * Update creation state
   */
  def updateCreationState(userId: Long, state: EventCreationState): Unit = {
    creationStates.put(userId, state)
  }
  
  /**
   * Remove creation state
   */
  def removeCreationState(userId: Long): Unit = {
    creationStates.remove(userId)
  }

  // ========== EDIT COMMAND ==========

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
        val dateTimeFormatter = new SimpleDateFormat("yyyy-MM-dd HH:mm")
        
        val modal = Modal.create(s"event_edit:$eventId", "Edit Event")
          .addActionRow(
            TextInput.create("title", "Title", TextInputStyle.SHORT)
              .setValue(existingEvent.title)
              .setRequired(false).build()
          )
          .addActionRow(
            TextInput.create("datetime", "Date & Time (YYYY-MM-DD HH:MM)", TextInputStyle.SHORT)
              .setValue(dateTimeFormatter.format(existingEvent.eventTime))
              .setRequired(false).build()
          )
          .addActionRow(
            TextInput.create("tank", "Tank slots", TextInputStyle.SHORT)
              .setValue(existingEvent.tankLimit.toString)
              .setRequired(false).build()
          )
          .addActionRow(
            TextInput.create("healer", "Healer slots", TextInputStyle.SHORT)
              .setValue(existingEvent.healerLimit.toString)
              .setRequired(false).build()
          )
          .addActionRow(
            TextInput.create("dps", "DPS slots", TextInputStyle.SHORT)
              .setValue(existingEvent.dpsLimit.toString)
              .setRequired(false).build()
          )
          .build()
        
        event.replyModal(modal).queue()
    }
  }
  
  def handleModalEdit(event: ModalInteractionEvent, eventId: Int): Unit = {
    event.deferReply(true).queue()

    val existingOpt = eventService.getEvent(eventId)
    if (existingOpt.isEmpty) {
      event.getHook.sendMessage("❌ Event not found").setEphemeral(true).queue()
      return
    }

    val existing = existingOpt.get

    val title = Option(event.getValue("title")).map(_.getAsString).filter(_.nonEmpty).getOrElse(existing.title)
    val dateTimeStr = Option(event.getValue("datetime")).map(_.getAsString).filter(_.nonEmpty)
    val tankStr = Option(event.getValue("tank")).map(_.getAsString).filter(_.nonEmpty)
    val healerStr = Option(event.getValue("healer")).map(_.getAsString).filter(_.nonEmpty)
    val dpsStr = Option(event.getValue("dps")).map(_.getAsString).filter(_.nonEmpty)

    val eventTime = dateTimeStr match {
      case Some(dt) =>
        Try(new Timestamp(dateFormat.parse(dt).getTime)).getOrElse(existing.eventTime)
      case None => existing.eventTime
    }

    val tankLimit = tankStr.flatMap(s => Try(s.toInt).toOption).getOrElse(existing.tankLimit)
    val healerLimit = healerStr.flatMap(s => Try(s.toInt).toOption).getOrElse(existing.healerLimit)
    val dpsLimit = dpsStr.flatMap(s => Try(s.toInt).toOption).getOrElse(existing.dpsLimit)

    val updated = existing.copy(
      title = title,
      eventTime = eventTime,
      tankLimit = tankLimit,
      healerLimit = healerLimit,
      dpsLimit = dpsLimit
    )

    eventService.updateEvent(updated) match {
      case Success(_) =>
        updateEventMessage(updated)
        event.getHook.sendMessage("✅ Event updated").setEphemeral(true).queue()

      case Failure(e) =>
        event.getHook.sendMessage(s"❌ ${e.getMessage}").setEphemeral(true).queue()
    }
  }

  def handleModalManage(event: ModalInteractionEvent, eventId: Int): Unit = {
    event.deferReply(true).queue()

    val existingOpt = eventService.getEvent(eventId)
    if (existingOpt.isEmpty) {
      event.getHook.sendMessage("❌ Event not found").setEphemeral(true).queue()
      return
    }

    val existing = existingOpt.get
    
    // Pobierz wartości z modala
    val removeUsersStr = Option(event.getValue("remove_users")).map(_.getAsString).filter(_.nonEmpty)
    val addUserStr = Option(event.getValue("add_user")).map(_.getAsString).filter(_.nonEmpty)
    val addRoleStr = Option(event.getValue("add_role")).map(_.getAsString).filter(_.nonEmpty)
    
    var results = List.empty[String]
    var errors = List.empty[String]
    var updated = false
    
    // 1. USUWANIE UŻYTKOWNIKÓW
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
    
    // 2. DODAWANIE UŻYTKOWNIKA
    if (addUserStr.isDefined && addRoleStr.isDefined) {
      val userIdStr = addUserStr.get
      val roleStr = addRoleStr.get.toLowerCase.trim
      
      (Try(userIdStr.toLong).toOption, EventRole.fromString(roleStr)) match {
        case (Some(userId), Some(role)) =>
          eventService.signupUser(eventId, userId, role) match {
            case Success(assignedRole) =>
              if (assignedRole == role) {
                results = results :+ s"✅ Added <@$userId> as **${role.name}**"
              } else {
                results = results :+ s"✅ Added <@$userId> to **${assignedRole.name}** (${role.name} was full)"
              }
              updated = true
            case Failure(e) =>
              errors = errors :+ s"❌ Failed to add <@$userId>: ${e.getMessage}"
          }
        case (None, _) =>
          errors = errors :+ s"❌ Invalid user ID: `$userIdStr`"
        case (_, None) =>
          errors = errors :+ s"❌ Invalid role: `$roleStr` (use: tank, healer, dps, or waitlist)"
      }
    } else if (addUserStr.isDefined || addRoleStr.isDefined) {
      errors = errors :+ "❌ To add a user, you must specify both user ID and role"
    }
    
    // Aktualizuj wiadomość eventu jeśli coś się zmieniło
    if (updated) {
      updateEventMessage(existing)
    }
    
    // Wyślij odpowiedź
    val response = new StringBuilder()
    
    if (results.nonEmpty) {
      response.append("**Changes applied:**\n")
      response.append(results.mkString("\n"))
    }
    
    if (errors.nonEmpty) {
      if (results.nonEmpty) response.append("\n\n")
      response.append("**Errors:**\n")
      response.append(errors.mkString("\n"))
    }
    
    if (results.isEmpty && errors.isEmpty) {
      response.append("❌ No changes made. Please specify users to remove or add.")
    }
    
    event.getHook.sendMessage(response.toString).setEphemeral(true).queue()
  }

  /**
   * Obsługuje modal dodawania użytkownika (z przycisku "Add User")
   */
  def handleModalManageAdd(event: ModalInteractionEvent, eventId: Int): Unit = {
    event.deferReply(true).queue()

    val existingOpt = eventService.getEvent(eventId)
    if (existingOpt.isEmpty) {
      event.getHook.sendMessage("❌ Event not found").setEphemeral(true).queue()
      return
    }

    val existing = existingOpt.get
    
    // Pobierz wartości z modala
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
            val message = if (assignedRole == role) {
              s"✅ Added <@$userId> as **${role.name}**"
            } else {
              s"✅ Added <@$userId> to **${assignedRole.name}** (${role.name} was full)"
            }
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
  
  /**
   * Listuje wszystkie serwery gdzie bot jest obecny - TYLKO dla admin guild
   */
  private def handleServers(event: SlashCommandInteractionEvent): Unit = {
    val currentGuildId = event.getGuild.getIdLong
    
    // Sprawdź czy komenda jest wykonywana na admin guild
    adminGuildId match {
      case None =>
        event.getHook.sendMessage("❌ Admin guild not configured").setEphemeral(true).queue()
        
      case Some(adminId) if currentGuildId != adminId =>
        event.getHook.sendMessage("❌ This command can only be used on the admin guild").setEphemeral(true).queue()
        
      case Some(adminId) if currentGuildId == adminId =>
        // Admin guild - wylistuj serwery
        val guilds = jda.getGuilds.asScala.toList.sortBy(_.getName.toLowerCase)
        
        if (guilds.isEmpty) {
          event.getHook.sendMessage("❌ Bot is not in any guilds").setEphemeral(true).queue()
          return
        }
        
        val embed = new net.dv8tion.jda.api.EmbedBuilder()
          .setTitle("🌐 Bot Guilds")
          .setDescription(s"Bot is present in **${guilds.size}** guilds:")
          .setColor(Color.BLUE)
        
        // Podziel serwery na chunki (max 25 fields w embedzie)
        guilds.take(25).foreach { guild =>
          val eventCount = eventService.getActiveEvents(guild.getIdLong).size
          embed.addField(
            guild.getName,
            s"ID: `${guild.getIdLong}`\nActive events: $eventCount",
            true
          )
        }
        
        if (guilds.size > 25) {
          embed.setFooter(s"Showing first 25 of ${guilds.size} guilds")
        }
        
        event.getHook.sendMessageEmbeds(embed.build()).setEphemeral(true).queue()
    }
  }

  private def handleClose(event: SlashCommandInteractionEvent): Unit =
    simpleEventAction(event, eventService.closeEvent, "closed")

  private def handleOpen(event: SlashCommandInteractionEvent): Unit =
    simpleEventAction(event, eventService.openEvent, "opened")

  private def handleDelete(event: SlashCommandInteractionEvent): Unit =
    simpleEventAction(event, eventService.deleteEvent, "deleted")
  
  /**
   * Usuwa eventy z konkretnego serwera - TYLKO dla admin guild!
   * WYMAGA parametru guild - nie można usunąć ze wszystkich serwerów naraz (bezpieczeństwo)
   */
  private def handleClean(event: SlashCommandInteractionEvent): Unit = {
    val currentGuildId = event.getGuild.getIdLong
    
    // Sprawdź czy komenda jest wykonywana na admin guild
    adminGuildId match {
      case None =>
        event.getHook.sendMessage("❌ Admin guild not configured. Set EVENT_ADMIN_GUILD_ID in prod.env").setEphemeral(true).queue()
        
      case Some(adminId) if currentGuildId != adminId =>
        event.getHook.sendMessage("❌ This command can only be used on the admin guild").setEphemeral(true).queue()
        logger.warn(s"User ${event.getUser.getIdLong} tried to use /event clean on guild $currentGuildId but only $adminId is allowed")
        
      case Some(adminId) if currentGuildId == adminId =>
        // Admin guild - wykonaj clean (guild jest REQUIRED więc zawsze będzie)
        val guildIdentifier = event.getOption("guild").getAsString
        
        // Znajdź guild
        findGuildByIdentifier(guildIdentifier) match {
          case None =>
            event.getHook.sendMessage(s"❌ Guild not found: `$guildIdentifier`\nUse guild ID or exact name\n\nTip: Use `/event servers` to list available guilds").setEphemeral(true).queue()
            
          case Some(guild) =>
            eventService.deleteEventsByGuild(guild.getIdLong) match {
              case Success(count) =>
                event.getHook.sendMessage(s"✅ Successfully deleted **$count** events from **${guild.getName}**").setEphemeral(true).queue()
                logger.info(s"Admin guild $adminId: deleted $count events from guild ${guild.getName} (${guild.getIdLong}) using /event clean")
                
              case Failure(e) =>
                event.getHook.sendMessage(s"❌ Failed to clean events from ${guild.getName}: ${e.getMessage}").setEphemeral(true).queue()
                logger.error(s"Failed to clean events from guild ${guild.getIdLong}", e)
            }
        }
    }
  }
  
  /**
   * Znajduje guild po ID lub nazwie
   */
  private def findGuildByIdentifier(identifier: String): Option[net.dv8tion.jda.api.entities.Guild] = {
    // Próbuj jako ID (Long)
    val byId = Try(identifier.toLong).toOption.flatMap { guildId =>
      Option(jda.getGuildById(guildId))
    }
    
    if (byId.isDefined) {
      return byId
    }
    
    // Próbuj jako nazwa (case-insensitive)
    val guilds = jda.getGuilds.asScala.toList
    guilds.find(_.getName.equalsIgnoreCase(identifier))
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
        eventService.getEvent(idOpt.get).foreach(updateEventMessage(_))
        event.getHook.sendMessage(s"✅ Event ${label}").setEphemeral(true).queue()
      case Failure(e) =>
        event.getHook.sendMessage(s"❌ ${e.getMessage}").setEphemeral(true).queue()
    }
  }

  private def updateEventMessage(event: Event, prependText: String = ""): Unit = {
    val channel = jda.getChannelById(classOf[net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel], event.channelId)
    if (channel == null) return

    channel.retrieveMessageById(event.messageId).queue { msg =>
      val signups = eventService.getSignupsByRole(event.id)
      val embed = embedBuilder.buildEventEmbed(event, signups)
      val buttons = EventButtons.createEventButtons(event.id, event.active)

      val editAction = if (prependText.nonEmpty) {
        msg.editMessage(prependText).setEmbeds(embed)
      } else {
        msg.editMessageEmbeds(embed)
      }

      editAction.setComponents(buttons.asJava).queue()
    }
  }

  def hasPermission(event: SlashCommandInteractionEvent): Boolean =
    event.getMember != null &&
      (event.getMember.hasPermission(Permission.MANAGE_CHANNEL) ||
        event.getMember.hasPermission(Permission.ADMINISTRATOR))
  
  /**
   * Pobiera wybranego użytkownika do dodania
   */
  def getSelectedUserToAdd(key: String): Option[List[Long]] = {
    selectedUsersToAdd.get(key)
  }
  
  /**
   * Zapisuje wybranego użytkownika do dodania
   */
  def setSelectedUserToAdd(key: String, userIds: List[Long]): Unit = {
    selectedUsersToAdd.put(key, userIds)
  }
  
  /**
   * Czyści wybranego użytkownika do dodania
   */
  def clearSelectedUserToAdd(key: String): Unit = {
    selectedUsersToAdd.remove(key)
  }
  
  /**
   * Dodaje użytkownika do eventu
   */
  def addUserToEvent(eventId: Int, userId: Long, role: EventRole): Try[EventRole] = {
    eventService.getEvent(eventId) match {
      case None =>
        Failure(new Exception(s"Event $eventId not found"))
        
      case Some(event) =>
        eventService.signupUser(eventId, userId, role) match {
          case Success(assignedRole) =>
            // Aktualizuj wiadomość eventu
            updateEventMessage(event)
            Success(assignedRole)
            
          case failure => failure
        }
    }
  }
}