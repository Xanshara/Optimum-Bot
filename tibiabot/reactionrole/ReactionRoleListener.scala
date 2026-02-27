package com.tibiabot.reactionrole

import com.tibiabot.Config
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.{ButtonInteractionEvent, StringSelectInteractionEvent}
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.events.message.react.{MessageReactionAddEvent, MessageReactionRemoveEvent}
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.{EmbedBuilder, Permission}
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu
import net.dv8tion.jda.api.interactions.components.text.{TextInput, TextInputStyle}
import net.dv8tion.jda.api.interactions.modals.Modal
import net.dv8tion.jda.api.interactions.components.ActionRow

import java.awt.Color
import scala.jdk.CollectionConverters._
import scala.util.{Try, Success, Failure}
import scala.collection.concurrent.TrieMap

/**
 * V2 - Listener z Select Menu zamiast Modali (UX jak Carl-bot!)
 */
class ReactionRoleListener(manager: ReactionRoleManager) extends ListenerAdapter with StrictLogging {

  // Tymczasowy stan sesji dla multi-step procesu dodawania roli
  case class AddRoleSession(
    userId: Long,
    messageId: Option[String] = None,
    roleId: Option[String] = None,
    emoji: Option[String] = None,
    mode: String = "normal",
    description: String = ""
  )
  
  // Przechowywanie sesji użytkowników
  private val addRoleSessions = new TrieMap[Long, AddRoleSession]()
  
  // Tymczasowy stan dla tworzenia wiadomości
  case class CreateMessageSession(
    userId: Long,
    channelId: Option[String] = None,
    title: Option[String] = None,
    description: Option[String] = None
  )
  
  private val createMessageSessions = new TrieMap[Long, CreateMessageSession]()

  /**
   * Obsługa reakcji - dodawanie ról
   */
  override def onMessageReactionAdd(event: MessageReactionAddEvent): Unit = {
    if (event.getUser.isBot) return

    val guildId = event.getGuild.getId
    val messageId = event.getMessageId
    val emoji = manager.emojiToString(event.getEmoji)
    
    logger.debug(s"Reaction added: $emoji on message $messageId by user ${event.getUserId}")

    manager.findReactionRole(guildId, messageId, emoji) match {
      case Some(config) =>
        val role = event.getGuild.getRoleById(config.roleId)
        
        if (role == null) {
          logger.error(s"Role ${config.roleId} not found for reaction role")
          return
        }

        event.getGuild.retrieveMemberById(event.getUserId).queue(
          member => {
            // Tryb unique - usuń inne role z tej wiadomości
            if (config.mode == "unique") {
              val otherRoles = manager.getReactionRolesForMessage(guildId, messageId)
                .filter(_.roleId != config.roleId)
                .flatMap(cfg => Option(event.getGuild.getRoleById(cfg.roleId)))
                .filter(member.getRoles.asScala.contains)
              
              otherRoles.foreach { otherRole =>
                event.getGuild.removeRoleFromMember(member, otherRole).queue()
                logger.debug(s"Removed role ${otherRole.getName} from ${member.getUser.getName} (unique mode)")
              }
            }
            
            // Dodaj nową rolę
            if (!member.getRoles.asScala.contains(role)) {
              event.getGuild.addRoleToMember(member, role).queue(
                _ => logger.info(s"Added role ${role.getName} to ${member.getUser.getName}"),
                error => logger.error(s"Failed to add role ${role.getName} to ${member.getUser.getName}", error)
              )
            }
          },
          error => logger.error(s"Failed to retrieve member ${event.getUserId}", error)
        )

      case None =>
        logger.debug(s"No reaction role config found for message $messageId and emoji $emoji")
    }
  }

  /**
   * Obsługa reakcji - usuwanie ról
   */
  override def onMessageReactionRemove(event: MessageReactionRemoveEvent): Unit = {
    if (event.getUser.isBot) return

    val guildId = event.getGuild.getId
    val messageId = event.getMessageId
    val emoji = manager.emojiToString(event.getEmoji)

    manager.findReactionRole(guildId, messageId, emoji) match {
      case Some(config) =>
        // W trybie "verify" nie usuwamy roli po usunięciu reakcji
        if (config.mode == "verify") {
          logger.debug(s"Verify mode - not removing role for removed reaction")
          return
        }

        val role = event.getGuild.getRoleById(config.roleId)
        
        if (role == null) {
          logger.error(s"Role ${config.roleId} not found for reaction role")
          return
        }

        event.getGuild.retrieveMemberById(event.getUserId).queue(
          member => {
            if (member.getRoles.asScala.contains(role)) {
              event.getGuild.removeRoleFromMember(member, role).queue(
                _ => logger.info(s"Removed role ${role.getName} from ${member.getUser.getName}"),
                error => logger.error(s"Failed to remove role ${role.getName} from ${member.getUser.getName}", error)
              )
            }
          },
          error => logger.error(s"Failed to retrieve member ${event.getUserId}", error)
        )

      case None =>
        logger.debug(s"No reaction role config found for message $messageId and emoji $emoji")
    }
  }

  /**
   * Obsługa komendy /reactionrole
   */
  override def onSlashCommandInteraction(event: SlashCommandInteractionEvent): Unit = {
    if (event.getName != "reactionrole") return

    logger.info(s"🎯 onSlashCommandInteraction called for /reactionrole")
    logger.info(s"🔍 Is event acknowledged? ${event.isAcknowledged}")

    if (event.isAcknowledged) {
      logger.error("❌ Event ALREADY ACKNOWLEDGED before our code! Another listener must be handling it.")
      return
    }

    val guild = event.getGuild
    val member = event.getMember

    if (member == null || (!member.hasPermission(Permission.MANAGE_ROLES) && !member.hasPermission(Permission.MANAGE_SERVER))) {
      logger.info("🔒 User lacks permissions, sending error reply")
      event.reply(s"${Config.noEmoji} You need **Manage Roles** or **Manage Server** permission to use this command.")
        .setEphemeral(true)
        .queue()
      return
    }

    logger.info("✅ User has permissions, deferring reply...")

    try {
      event.deferReply(true).queue(
        _ => logger.info("✅ deferReply successful"),
        error => logger.error(s"❌ deferReply failed: ${error.getMessage}", error)
      )
    } catch {
      case ex: Exception =>
        logger.error("❌ Exception when calling deferReply", ex)
        return
    }

    logger.info("🔄 Initializing table...")

    Try {
      manager.initializeTable(guild.getId)
    } match {
      case Success(_) =>
        logger.info("✅ Table initialized, showing main menu")
        showMainMenuDeferred(event)
      case Failure(ex) =>
        logger.error(s"Failed to initialize table for guild ${guild.getId}", ex)
        event.getHook.sendMessage(s"${Config.noEmoji} Failed to initialize database. Please try again.").queue()
    }
  }

  /**
   * Wyświetla główne menu
   */
  private def showMainMenuDeferred(event: SlashCommandInteractionEvent): Unit = {
    val guild = event.getGuild
    val configs = manager.getAllReactionRoles(guild.getId)
    
    val embed = new EmbedBuilder()
      .setTitle("⚙️ Reaction Roles Management")
      .setDescription(
        "**Welcome to Reaction Roles setup!**\n\n" +
        "Choose an action below to manage reaction roles on your server.\n\n" +
        s"**Current reaction roles:** ${configs.size} configured"
      )
      .setColor(Color.BLUE)
      .addField(
        "📝 Create Message",
        "Create a new embed message for reaction roles",
        false
      )
      .addField(
        "➕ Add Role",
        "Add a reaction role to an existing message",
        false
      )
      .addField(
        "📋 Manage Existing",
        "View, edit, or delete existing reaction roles",
        false
      )
      .setFooter("Click a button to get started")
      .build()

    val buttons = ActionRow.of(
      Button.primary("rr_create", "📝 Create Message"),
      Button.success("rr_add", "➕ Add Role"),
      Button.secondary("rr_manage", "📋 Manage Existing")
    )

    event.getHook.sendMessageEmbeds(embed)
      .addComponents(buttons)
      .queue()
  }

  /**
   * Obsługa buttonów
   */
  override def onButtonInteraction(event: ButtonInteractionEvent): Unit = {
    val buttonId = event.getComponentId
    
    if (!buttonId.startsWith("rr_")) return

    logger.info(s"🎯 onButtonInteraction called for button: $buttonId")
    logger.info(s"🔍 Is event acknowledged? ${event.isAcknowledged}")

    if (event.isAcknowledged) {
      logger.error(s"❌ Button $buttonId ALREADY ACKNOWLEDGED by another listener!")
      return
    }

    val guild = event.getGuild
    val member = event.getMember

    if (member == null || (!member.hasPermission(Permission.MANAGE_ROLES) && !member.hasPermission(Permission.MANAGE_SERVER))) {
      logger.info("🔒 User lacks permissions for button")
      event.reply(s"${Config.noEmoji} You need **Manage Roles** permission to use this.")
        .setEphemeral(true)
        .queue()
      return
    }

    logger.info(s"✅ User has permissions, handling button action: $buttonId")

    try {
      buttonId match {
        case "rr_create" => 
          logger.info("📝 Starting Create Message flow")
          startCreateMessageFlow(event)
        case "rr_add" => 
          logger.info("➕ Starting Add Role flow")
          startAddRoleFlow(event)
        case "rr_manage" => 
          logger.info("📋 Calling showManageMenu")
          showManageMenu(event)
        case id if id.startsWith("rr_refresh_") => handleRefreshMessage(event, id.replace("rr_refresh_", ""))
        case id if id.startsWith("rr_delete_") => handleDeleteMessage(event, id.replace("rr_delete_", ""))
        case "rr_back_main" => event.deferEdit().queue(_ => showMainMenuEdit(event))
        case "rr_back_manage" => event.deferEdit().queue(_ => showManageMenuEdit(event))
        case _ if buttonId.startsWith("rr_create_channel_") => handleCreateChannelSelection(event)
        case _ if buttonId.startsWith("rr_add_custom_emoji") => handleCustomEmojiInput(event)
        case _ => ()
      }
    } catch {
      case ex: Exception =>
        logger.error(s"Error handling button interaction: $buttonId", ex)
        Try {
          if (!event.isAcknowledged) {
            event.reply(s"${Config.noEmoji} An error occurred: ${ex.getMessage}")
              .setEphemeral(true)
              .queue()
          } else {
            event.getHook.sendMessage(s"${Config.noEmoji} An error occurred: ${ex.getMessage}")
              .setEphemeral(true)
              .queue()
          }
        }
    }
  }

  /**
   * NOWY FLOW - Create Message ze Select Menu
   */
  private def startCreateMessageFlow(event: ButtonInteractionEvent): Unit = {
    event.deferReply(true).queue()
    
    val userId = event.getUser.getIdLong
    val guild = event.getGuild
    
    // Resetuj sesję
    createMessageSessions.put(userId, CreateMessageSession(userId))
    
    // Pokaż dropdown z kanałami
    val channels = guild.getTextChannels.asScala.take(25) // Discord limit
    
    if (channels.isEmpty) {
      event.getHook.sendMessage(s"${Config.noEmoji} No text channels found!").queue()
      return
    }
    
    val selectMenu = StringSelectMenu.create("rr_create_select_channel")
      .setPlaceholder("Select a channel for the message...")
      .setMinValues(1)
      .setMaxValues(1)
    
    channels.foreach { channel =>
      selectMenu.addOption(
        s"#${channel.getName}",
        channel.getId,
        s"ID: ${channel.getId}"
      )
    }
    
    val embed = new EmbedBuilder()
      .setTitle("📝 Create Reaction Role Message - Step 1/3")
      .setDescription("**Select a channel** where the reaction role message will be posted.")
      .setColor(Color.BLUE)
      .build()
    
    event.getHook.sendMessageEmbeds(embed)
      .addActionRow(selectMenu.build())
      .queue()
  }

  /**
   * NOWY FLOW - Add Role ze Select Menu
   */
  private def startAddRoleFlow(event: ButtonInteractionEvent): Unit = {
    event.deferReply(true).queue()
    
    val userId = event.getUser.getIdLong
    val guild = event.getGuild
    
    // Resetuj sesję
    addRoleSessions.put(userId, AddRoleSession(userId))
    
    // Pobierz wszystkie wiadomości z bazy (włącznie z nowymi bez ról)
    val messages = manager.getAllMessageIds(guild.getId)
    
    if (messages.isEmpty) {
      event.getHook.sendMessage(
        s"${Config.letterEmoji} **No reaction role messages found!**\n\n" +
        s"Use **📝 Create Message** button first to create a message."
      ).queue()
      return
    }
    
    // Pobierz info o rolach dla każdej wiadomości
    val configs = manager.getAllReactionRoles(guild.getId)
    val roleCountByMessage = configs.groupBy(_.messageId).view.mapValues(_.size).toMap
    
    // Buduj dropdown z wiadomościami
    val selectMenu = StringSelectMenu.create("rr_add_select_message")
      .setPlaceholder("Select a message to add role to...")
      .setMinValues(1)
      .setMaxValues(1)
    
    messages.take(25).foreach { case (messageId, channelId, titleFromDb) =>
      val channel = guild.getTextChannelById(channelId)
      val channelName = if (channel != null) s"#${channel.getName}" else "Unknown"
      val roleCount = roleCountByMessage.getOrElse(messageId, 0)
      
      // Użyj tytułu z bazy jeśli jest (z placeholder), albo pobierz z wiadomości
      val title = if (titleFromDb.nonEmpty) {
        titleFromDb
      } else {
        Try {
          channel.retrieveMessageById(messageId).complete()
        }.toOption.flatMap { message =>
          message.getEmbeds.asScala.headOption
            .flatMap(e => Option(e.getTitle))
        }.getOrElse("Untitled")
      }
      
      selectMenu.addOption(
        s"$channelName: $title ($roleCount roles)",
        messageId,
        s"ID: $messageId"
      )
    }
    
    val embed = new EmbedBuilder()
      .setTitle("➕ Add Reaction Role - Step 1/4")
      .setDescription("**Select a message** to add a reaction role to.")
      .setColor(Color.GREEN)
      .build()
    
    event.getHook.sendMessageEmbeds(embed)
      .addActionRow(selectMenu.build())
      .queue()
  }

  /**
   * Obsługa Select Menu
   */
  override def onStringSelectInteraction(event: StringSelectInteractionEvent): Unit = {
    val selectId = event.getComponentId
    
    if (!selectId.startsWith("rr_")) return
    
    val guild = event.getGuild
    val userId = event.getUser.getIdLong
    
    selectId match {
      // Create Message Flow
      case "rr_create_select_channel" =>
        handleCreateChannelSelect(event)
        
      // Add Role Flow  
      case "rr_add_select_message" =>
        handleAddMessageSelect(event)
      case "rr_add_select_role" =>
        handleAddRoleSelect(event)
      case "rr_add_select_emoji" =>
        handleAddEmojiSelect(event)
      case "rr_add_select_mode" =>
        handleAddModeSelect(event)
        
      // Manage Flow
      case "rr_select_message" =>
        handleManageMessageSelect(event)
        
      case _ => ()
    }
  }

  // ===== CREATE MESSAGE FLOW HANDLERS =====
  
  private def handleCreateChannelSelect(event: StringSelectInteractionEvent): Unit = {
    val userId = event.getUser.getIdLong
    val channelId = event.getValues.get(0)
    
    // Zapisz wybór kanału
    createMessageSessions.get(userId) match {
      case Some(session) =>
        createMessageSessions.put(userId, session.copy(channelId = Some(channelId)))
        
        // Teraz poproś o tytuł i opis przez modal
        val modal = Modal.create("rr_create_modal", "Create Message - Step 2/3")
          .addActionRow(
            TextInput.create("title", "Title", TextInputStyle.SHORT)
              .setPlaceholder("e.g. Server Roles")
              .setRequired(true)
              .setMaxLength(256)
              .build()
          )
          .addActionRow(
            TextInput.create("description", "Description", TextInputStyle.PARAGRAPH)
              .setPlaceholder("e.g. React below to get your role!")
              .setRequired(true)
              .setMaxLength(2000)
              .build()
          )
          .build()
        
        event.replyModal(modal).queue()
        
      case None =>
        event.reply(s"${Config.noEmoji} Session expired. Please start over.")
          .setEphemeral(true)
          .queue()
    }
  }
  
  private def handleCreateChannelSelection(event: ButtonInteractionEvent): Unit = {
    // This is called if we use buttons instead of modal for title/desc
    // For now we use modal as it's cleaner for text input
  }

  // ===== ADD ROLE FLOW HANDLERS =====
  
  private def handleAddMessageSelect(event: StringSelectInteractionEvent): Unit = {
    event.deferReply(true).queue()
    
    val userId = event.getUser.getIdLong
    val messageId = event.getValues.get(0)
    val guild = event.getGuild
    
    addRoleSessions.get(userId) match {
      case Some(session) =>
        addRoleSessions.put(userId, session.copy(messageId = Some(messageId)))
        
        // Pokaż dropdown z rolami
        val roles = guild.getRoles.asScala
          .filter(r => !r.isPublicRole && !r.isManaged)
          .take(25)
        
        if (roles.isEmpty) {
          event.getHook.sendMessage(s"${Config.noEmoji} No roles found!").queue()
          return
        }
        
        val selectMenu = StringSelectMenu.create("rr_add_select_role")
          .setPlaceholder("Select a role...")
          .setMinValues(1)
          .setMaxValues(1)
        
        roles.foreach { role =>
          selectMenu.addOption(
            role.getName,
            role.getId,
            s"ID: ${role.getId}"
          )
        }
        
        val embed = new EmbedBuilder()
          .setTitle("➕ Add Reaction Role - Step 2/4")
          .setDescription("**Select a role** that will be assigned when users react.")
          .setColor(Color.GREEN)
          .build()
        
        event.getHook.sendMessageEmbeds(embed)
          .addActionRow(selectMenu.build())
          .queue()
          
      case None =>
        event.getHook.sendMessage(s"${Config.noEmoji} Session expired. Please start over.").queue()
    }
  }
  
  private def handleAddRoleSelect(event: StringSelectInteractionEvent): Unit = {
    event.deferReply(true).queue()
    
    val userId = event.getUser.getIdLong
    val roleId = event.getValues.get(0)
    
    addRoleSessions.get(userId) match {
      case Some(session) =>
        addRoleSessions.put(userId, session.copy(roleId = Some(roleId)))
        
        // Pokaż dropdown z najpopularniejszymi emoji + opcja custom
        val selectMenu = StringSelectMenu.create("rr_add_select_emoji")
          .setPlaceholder("Select an emoji or choose custom...")
          .setMinValues(1)
          .setMaxValues(1)
          .addOption("👍 Thumbs up", "👍", "Popular choice")
          .addOption("⚔️ Crossed swords", "⚔️", "For warrior/fighter roles")
          .addOption("🛡️ Shield", "🛡️", "For tank/defense roles")
          .addOption("💚 Green heart", "💚", "For healer roles")
          .addOption("🏹 Bow and arrow", "🏹", "For archer roles")
          .addOption("📚 Books", "📚", "For mage roles")
          .addOption("⭐ Star", "⭐", "For VIP/special roles")
          .addOption("✅ Check mark", "✅", "For verification")
          .addOption("❌ Cross mark", "❌", "For removal")
          .addOption("🎮 Game controller", "🎮", "For gaming roles")
          .addOption("✏️ Type custom emoji...", "CUSTOM", "Use custom server emoji")
          .build()
        
        val embed = new EmbedBuilder()
          .setTitle("➕ Add Reaction Role - Step 3/4")
          .setDescription(
            "**Select an emoji** or choose 'Type custom emoji' to use your server's custom emoji.\n\n" +
            "**Custom emoji format:**\n" +
            "• `:emojiname:` (e.g. `:tibia:`)\n" +
            "• `name:123456` (e.g. `tibia:1234567890`)"
          )
          .setColor(Color.GREEN)
          .build()
        
        event.getHook.sendMessageEmbeds(embed)
          .addActionRow(selectMenu)
          .queue()
          
      case None =>
        event.getHook.sendMessage(s"${Config.noEmoji} Session expired. Please start over.").queue()
    }
  }
  
  private def handleAddEmojiSelect(event: StringSelectInteractionEvent): Unit = {
    val userId = event.getUser.getIdLong
    val selectedEmoji = event.getValues.get(0)
    
    if (selectedEmoji == "CUSTOM") {
      // User wants custom emoji - show modal
      val modal = Modal.create("rr_add_custom_emoji", "Custom Emoji")
        .addActionRow(
          TextInput.create("emoji", "Emoji", TextInputStyle.SHORT)
            .setPlaceholder(":emojiname: or name:123456")
            .setRequired(true)
            .setMaxLength(100)
            .build()
        )
        .build()
      
      event.replyModal(modal).queue()
    } else {
      // Standard emoji selected
      event.deferReply(true).queue()
      
      addRoleSessions.get(userId) match {
        case Some(session) =>
          addRoleSessions.put(userId, session.copy(emoji = Some(selectedEmoji)))
          showModeSelect(event.getHook, userId)
          
        case None =>
          event.getHook.sendMessage(s"${Config.noEmoji} Session expired. Please start over.").queue()
      }
    }
  }
  
  private def showModeSelect(hook: net.dv8tion.jda.api.interactions.InteractionHook, userId: Long): Unit = {
    val selectMenu = StringSelectMenu.create("rr_add_select_mode")
      .setPlaceholder("Select a mode...")
      .setMinValues(1)
      .setMaxValues(1)
      .addOption("⚙️ Normal", "normal", "Default - users can have multiple roles")
      .addOption("🔒 Unique", "unique", "Users can only have ONE role from this message")
      .addOption("✅ Verify", "verify", "Role persists even after unreacting")
      .build()
    
    val embed = new EmbedBuilder()
      .setTitle("➕ Add Reaction Role - Step 4/4")
      .setDescription(
        "**Select a mode:**\n\n" +
        "**⚙️ Normal** - Standard behavior. Users can have multiple roles from this message.\n\n" +
        "**🔒 Unique** - Users can only have ONE role from this message. Selecting a new role removes the previous one.\n" +
        "_Example: Class selection (Warrior/Mage/Archer - only one!)_\n\n" +
        "**✅ Verify** - Role is permanent. Unreacting won't remove the role.\n" +
        "_Example: Rules acceptance, verification_"
      )
      .setColor(Color.GREEN)
      .build()
    
    hook.sendMessageEmbeds(embed)
      .addActionRow(selectMenu)
      .queue()
  }
  
  private def handleAddModeSelect(event: StringSelectInteractionEvent): Unit = {
    event.deferReply(true).queue()
    
    val userId = event.getUser.getIdLong
    val mode = event.getValues.get(0)
    val guild = event.getGuild
    
    addRoleSessions.get(userId) match {
      case Some(session) if session.messageId.isDefined && session.roleId.isDefined && session.emoji.isDefined =>
        // Wszystkie dane zebrane - zapisz!
        val messageId = session.messageId.get
        val roleId = session.roleId.get
        val emoji = session.emoji.get
        
        val role = guild.getRoleById(roleId)
        if (role == null) {
          event.getHook.sendMessage(s"${Config.noEmoji} Role not found!").queue()
          addRoleSessions.remove(userId)
          return
        }
        
        // Znajdź channel_id dla tej wiadomości (używamy getAllMessageIds żeby uwzględnić placeholdery)
        val messages = manager.getAllMessageIds(guild.getId)
        val messageInfo = messages.find(_._1 == messageId)
        
        messageInfo match {
          case Some((_, channelId, _)) =>
            val config = ReactionRoleConfig(
              guildId = guild.getId,
              channelId = channelId,
              messageId = messageId,
              emoji = emoji,
              roleId = roleId,
              mode = mode,
              description = ""
            )
            
            manager.addReactionRole(config) match {
              case Success(_) =>
                // Dodaj reakcję do wiadomości
                val channel = guild.getTextChannelById(channelId)
                if (channel != null) {
                  Try {
                    val message = channel.retrieveMessageById(messageId).complete()
                    val emojiObj = manager.parseEmoji(guild, emoji)
                    message.addReaction(emojiObj).queue(
                      _ => logger.info(s"✅ Added reaction $emoji to message $messageId"),
                      error => logger.error(s"❌ Failed to add reaction: ${error.getMessage}", error)
                    )
                  } match {
                    case Failure(ex) =>
                      logger.error(s"❌ Exception when adding reaction $emoji to message $messageId", ex)
                      event.getHook.sendMessage(
                        s"⚠️ **Warning:** Reaction role saved but failed to add emoji to message.\n" +
                        s"**Error:** ${ex.getMessage}\n\n" +
                        s"Use `/reactionrole` → **📋 Manage Existing** → **🔄 Refresh Reactions** to try again."
                      ).queue()
                    case _ => // Success handled in queue callbacks
                  }
                } else {
                  logger.error(s"❌ Channel $channelId not found!")
                  event.getHook.sendMessage(
                    s"⚠️ **Warning:** Reaction role saved but channel not found.\n" +
                    s"Cannot add emoji reaction to message."
                  ).queue()
                }
                
                val modeText = mode match {
                  case "unique" => "🔒 **Unique** - Users can only have one role from this message"
                  case "verify" => "✅ **Verify** - Role persists after unreacting"
                  case _ => "⚙️ **Normal** - Standard behavior"
                }
                
                event.getHook.sendMessage(
                  s"${Config.yesEmoji} **Reaction role added successfully!**\n\n" +
                  s"**Emoji:** $emoji\n" +
                  s"**Role:** ${role.getAsMention}\n" +
                  s"**Mode:** $modeText\n" +
                  s"**Message ID:** `$messageId`\n\n" +
                  s"✅ Users can now react with $emoji to get the role!"
                ).queue()
                
                logger.info(s"Successfully added reaction role: $emoji -> ${role.getName} in message $messageId (mode: $mode)")
                
              case Failure(ex) =>
                event.getHook.sendMessage(s"${Config.noEmoji} Failed to add reaction role: ${ex.getMessage}").queue()
                logger.error("Failed to add reaction role", ex)
            }
            
          case None =>
            event.getHook.sendMessage(
              s"${Config.noEmoji} **Message not found in database!**\n\n" +
              s"This shouldn't happen. Message ID: `$messageId`"
            ).queue()
            logger.error(s"Message $messageId not found in getAllMessageIds!")
        }
        
        // Wyczyść sesję
        addRoleSessions.remove(userId)
        
      case _ =>
        event.getHook.sendMessage(s"${Config.noEmoji} Session invalid or expired. Please start over.").queue()
        addRoleSessions.remove(userId)
    }
  }
  
  private def handleCustomEmojiInput(event: ButtonInteractionEvent): Unit = {
    // Handled via modal in handleAddEmojiSelect
  }

  /**
   * Obsługa modali (tylko dla text input gdzie to konieczne)
   */
  override def onModalInteraction(event: ModalInteractionEvent): Unit = {
    val modalId = event.getModalId
    
    if (!modalId.startsWith("rr_")) return

    event.deferReply(true).queue()

    modalId match {
      case "rr_create_modal" => handleCreateModalSubmit(event)
      case "rr_add_custom_emoji" => handleCustomEmojiModalSubmit(event)
      case _ => ()
    }
  }
  
  private def handleCreateModalSubmit(event: ModalInteractionEvent): Unit = {
    val userId = event.getUser.getIdLong
    val title = event.getValue("title").getAsString
    val description = event.getValue("description").getAsString
    val guild = event.getGuild
    
    createMessageSessions.get(userId) match {
      case Some(session) if session.channelId.isDefined =>
        val channelId = session.channelId.get
        val channel = guild.getTextChannelById(channelId)
        
        if (channel == null) {
          event.getHook.sendMessage(s"${Config.noEmoji} Channel not found!").queue()
          createMessageSessions.remove(userId)
          return
        }
        
        val embed = new EmbedBuilder()
          .setTitle(title)
          .setDescription(description)
          .setColor(Color.BLUE)
          .setFooter("React below to get roles!")
          .build()
        
        channel.sendMessageEmbeds(embed).queue(
          message => {
            // Zarejestruj wiadomość w bazie (placeholder) żeby pojawiła się w Add Role
            manager.registerMessage(guild.getId, channel.getId, message.getId, title) match {
              case Success(_) =>
                logger.info(s"Registered message ${message.getId} in database")
              case Failure(ex) =>
                logger.error(s"Failed to register message ${message.getId}", ex)
            }
            
            event.getHook.sendMessage(
              s"${Config.yesEmoji} **Message created successfully!**\n\n" +
              s"**Channel:** ${channel.getAsMention}\n" +
              s"**Message ID:** `${message.getId}`\n" +
              s"**Title:** $title\n\n" +
              s"💡 **Next step:** Use `/reactionrole` → **➕ Add Role** to configure roles for this message."
            ).queue()
            
            logger.info(s"Created reaction role message ${message.getId} in channel ${channel.getId}")
          },
          error => {
            event.getHook.sendMessage(s"${Config.noEmoji} Failed to create message: ${error.getMessage}").queue()
            logger.error("Failed to create message", error)
          }
        )
        
        createMessageSessions.remove(userId)
        
      case _ =>
        event.getHook.sendMessage(s"${Config.noEmoji} Session expired. Please start over.").queue()
        createMessageSessions.remove(userId)
    }
  }
  
  private def handleCustomEmojiModalSubmit(event: ModalInteractionEvent): Unit = {
    val userId = event.getUser.getIdLong
    val emoji = event.getValue("emoji").getAsString
    
    addRoleSessions.get(userId) match {
      case Some(session) =>
        addRoleSessions.put(userId, session.copy(emoji = Some(emoji)))
        showModeSelect(event.getHook, userId)
        
      case None =>
        event.getHook.sendMessage(s"${Config.noEmoji} Session expired. Please start over.").queue()
    }
  }

  /**
   * Manage existing - dropdown z wiadomościami
   */
  private def showManageMenu(event: ButtonInteractionEvent): Unit = {
    logger.info("showManageMenu called")
    event.deferEdit().queue()
    
    val guild = event.getGuild
    val configs = manager.getAllReactionRoles(guild.getId)

    if (configs.isEmpty) {
      event.getHook.sendMessage(s"${Config.letterEmoji} No reaction roles configured on this server yet.")
        .setEphemeral(true)
        .queue()
      return
    }

    val messageGroups = configs.groupBy(_.messageId).toList.take(25)

    val selectMenu = StringSelectMenu.create("rr_select_message")
      .setPlaceholder("Select a message to manage...")
      .setMinValues(1)
      .setMaxValues(1)

    messageGroups.foreach { case (messageId, messageConfigs) =>
      val channelId = messageConfigs.head.channelId
      val channel = guild.getTextChannelById(channelId)
      val channelName = if (channel != null) s"#${channel.getName}" else "Unknown Channel"
      val roleCount = messageConfigs.size
      
      selectMenu.addOption(
        s"$channelName ($roleCount roles)",
        messageId,
        s"Message ID: $messageId"
      )
    }

    val embed = new EmbedBuilder()
      .setTitle("📋 Manage Reaction Roles")
      .setDescription(
        s"**${configs.size} reaction role(s) configured**\n\n" +
        "Select a message from the dropdown below to view details and manage roles."
      )
      .setColor(Color.BLUE)
      .build()

    val backButton = ActionRow.of(
      Button.secondary("rr_back_main", "◀️ Back to Main Menu")
    )

    event.getHook.editOriginalEmbeds(embed)
      .setComponents(ActionRow.of(selectMenu.build()), backButton)
      .queue()
  }
  
  private def handleManageMessageSelect(event: StringSelectInteractionEvent): Unit = {
    event.deferEdit().queue()
    
    val guild = event.getGuild
    val messageId = event.getValues.get(0)
    val configs = manager.getReactionRolesForMessage(guild.getId, messageId)

    if (configs.isEmpty) {
      event.getHook.sendMessage(s"${Config.noEmoji} No reaction roles found for this message.")
        .setEphemeral(true)
        .queue()
      return
    }

    val channelId = configs.head.channelId
    val channel = guild.getTextChannelById(channelId)
    val channelMention = if (channel != null) channel.getAsMention else s"`$channelId`"

    val embed = new EmbedBuilder()
      .setTitle("📋 Reaction Roles Details")
      .setDescription(
        s"**Channel:** $channelMention\n" +
        s"**Message ID:** `$messageId`\n\n" +
        s"**Configured Roles (${configs.size}):**"
      )
      .setColor(Color.BLUE)

    configs.foreach { config =>
      val role = guild.getRoleById(config.roleId)
      val roleName = if (role != null) role.getName else s"Unknown (${config.roleId})"
      val modeTag = config.mode match {
        case "unique" => " 🔒"
        case "verify" => " ✅"
        case _ => ""
      }
      
      embed.addField(
        s"${config.emoji} → **$roleName**$modeTag",
        if (config.description.nonEmpty) config.description else "_No description_",
        false
      )
    }

    val buttons = ActionRow.of(
      Button.success("rr_refresh_" + messageId, "🔄 Refresh Reactions"),
      Button.danger("rr_delete_" + messageId, "🗑️ Delete All Roles"),
      Button.secondary("rr_back_manage", "◀️ Back")
    )

    event.getHook.editOriginalEmbeds(embed.build())
      .setComponents(buttons)
      .queue()
  }

  private def handleRefreshMessage(event: ButtonInteractionEvent, messageId: String): Unit = {
    val guild = event.getGuild
    
    event.deferEdit().queue()

    manager.addReactionsToMessage(guild, messageId) match {
      case Success(_) =>
        event.getHook.sendMessage(s"${Config.yesEmoji} Refreshed reactions on message `$messageId`")
          .setEphemeral(true)
          .queue()
      case Failure(ex) =>
        event.getHook.sendMessage(s"${Config.noEmoji} Failed to refresh reactions: ${ex.getMessage}")
          .setEphemeral(true)
          .queue()
        logger.error("Failed to refresh reactions", ex)
    }
  }

  private def handleDeleteMessage(event: ButtonInteractionEvent, messageId: String): Unit = {
    val guild = event.getGuild
    
    event.deferEdit().queue()

    manager.removeAllReactionRolesForMessage(guild.getId, messageId) match {
      case Success(count) =>
        if (count > 0) {
          event.getHook.sendMessage(s"${Config.yesEmoji} Deleted $count reaction role(s) from message `$messageId`")
            .setEphemeral(true)
            .queue()
          
          showManageMenuEdit(event)
        } else {
          event.getHook.sendMessage(s"${Config.noEmoji} No reaction roles found for message `$messageId`")
            .setEphemeral(true)
            .queue()
        }

      case Failure(ex) =>
        event.getHook.sendMessage(s"${Config.noEmoji} Failed to delete reaction roles: ${ex.getMessage}")
          .setEphemeral(true)
          .queue()
        logger.error("Failed to delete reaction roles", ex)
    }
  }

  private def showMainMenuEdit(event: ButtonInteractionEvent): Unit = {
    val guild = event.getGuild
    val configs = manager.getAllReactionRoles(guild.getId)
    
    val embed = new EmbedBuilder()
      .setTitle("⚙️ Reaction Roles Management")
      .setDescription(
        "**Welcome to Reaction Roles setup!**\n\n" +
        "Choose an action below to manage reaction roles on your server.\n\n" +
        s"**Current reaction roles:** ${configs.size} configured"
      )
      .setColor(Color.BLUE)
      .addField(
        "📝 Create Message",
        "Create a new embed message for reaction roles",
        false
      )
      .addField(
        "➕ Add Role",
        "Add a reaction role to an existing message",
        false
      )
      .addField(
        "📋 Manage Existing",
        "View, edit, or delete existing reaction roles",
        false
      )
      .setFooter("Click a button to get started")
      .build()

    val buttons = ActionRow.of(
      Button.primary("rr_create", "📝 Create Message"),
      Button.success("rr_add", "➕ Add Role"),
      Button.secondary("rr_manage", "📋 Manage Existing")
    )

    event.getHook.editOriginalEmbeds(embed)
      .setComponents(buttons)
      .queue()
  }

  private def showManageMenuEdit(event: ButtonInteractionEvent): Unit = {
    val guild = event.getGuild
    val configs = manager.getAllReactionRoles(guild.getId)

    if (configs.isEmpty) {
      val embed = new EmbedBuilder()
        .setTitle("📋 Manage Reaction Roles")
        .setDescription(s"${Config.letterEmoji} No reaction roles configured on this server yet.")
        .setColor(Color.BLUE)
        .build()

      val backButton = ActionRow.of(
        Button.secondary("rr_back_main", "◀️ Back to Main Menu")
      )

      event.getHook.editOriginalEmbeds(embed)
        .setComponents(backButton)
        .queue()
      return
    }

    val messageGroups = configs.groupBy(_.messageId).toList.take(25)

    val selectMenu = StringSelectMenu.create("rr_select_message")
      .setPlaceholder("Select a message to manage...")
      .setMinValues(1)
      .setMaxValues(1)

    messageGroups.foreach { case (messageId, messageConfigs) =>
      val channelId = messageConfigs.head.channelId
      val channel = guild.getTextChannelById(channelId)
      val channelName = if (channel != null) s"#${channel.getName}" else "Unknown Channel"
      val roleCount = messageConfigs.size
      
      selectMenu.addOption(
        s"$channelName ($roleCount roles)",
        messageId,
        s"Message ID: $messageId"
      )
    }

    val embed = new EmbedBuilder()
      .setTitle("📋 Manage Reaction Roles")
      .setDescription(
        s"**${configs.size} reaction role(s) configured**\n\n" +
        "Select a message from the dropdown below to view details and manage roles."
      )
      .setColor(Color.BLUE)
      .build()

    val backButton = ActionRow.of(
      Button.secondary("rr_back_main", "◀️ Back to Main Menu")
    )

    event.getHook.editOriginalEmbeds(embed)
      .setComponents(ActionRow.of(selectMenu.build()), backButton)
      .queue()
  }
}