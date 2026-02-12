package com.tibiabot.imbue

case class ImbueInfo(
  title: String,
  description: String,
  thumbnail: String
)

object ImbueData {

  val data: Map[String, ImbueInfo] = Map(

    // DAMAGE / LEECH
    "fire damage" -> ImbueInfo(
      "🔥 Fire Damage 🔥",
      "Konwertuje **50% obrażeń fizycznych** do obrażeń od ognia.\n\n" +
        "**Koszt:**\n" +
        "• 25 Fiery Hearts\n" +
        "• 5 Green Dragon Scales\n" +
        "• 5 Demon Horns",
      "https://tibia.fandom.com/wiki/Special:Redirect/file/Powerful_Scorch.png"
    ),

    "earth damage" -> ImbueInfo(
      "🌿 Earth Damage 🌿",
      "Konwertuje **50% obrażeń fizycznych** do obrażeń od ziemi.\n\n" +
        "**Koszt:**\n" +
        "• 25 Swamp Grass\n" +
        "• 20 Poisonous Slime\n" +
        "• 2 Slime Heart",
      "https://tibia.fandom.com/wiki/Special:Redirect/file/Powerful_Venom.png"
    ),

    "mana leech" -> ImbueInfo(
      "🔮 Mana Leech 🔮",
      "Dodaje **8% many** zależnie od obrażeń (100% szansy).\n\n" +
        "**Koszt:**\n" +
        "• 25 Rope Belts\n" +
        "• 25 Silencer Claws\n" +
        "• 5 Grimeleech Wings",
      "https://tibia.fandom.com/wiki/Special:Redirect/file/Powerful_Void.png"
    ),

    "life leech" -> ImbueInfo(
      "❤️ Life Leech ❤️",
      "Konwertuje **15% obrażeń** w HP (100% szansy).\n\n" +
        "**Koszt:**\n" +
        "• 25 Vampire Teeth\n" +
        "• 15 Bloody Pincers\n" +
        "• 5 Piece of Dead Brain",
      "https://tibia.fandom.com/wiki/Special:Redirect/file/Powerful_Vampirism.png"
    ),

    // SKILLS
    "magic level" -> ImbueInfo(
      "✨ Magic Level ✨",
      "Podnosi umiejętność władania magią o **4**.\n\n" +
        "**Koszt:**\n" +
        "• 25 Elvish Talismans\n" +
        "• 15 Broken Shamanic Staffs\n" +
        "• 15 Strands of Medusa Hair",
      "https://tibia.fandom.com/wiki/Special:Redirect/file/Powerful_Epiphany.png"
    ),

    "critical hit" -> ImbueInfo(
      "🎯 Critical Hit 🎯",
      "Zwiększa szansę na trafienie krytyczne.\n\n" +
        "**Koszt:**\n" +
        "• 20 Protective Charms\n" +
        "• 25 Sabreteeth\n" +
        "• 5 Vexclaw Talons",
      "https://tibia.fandom.com/wiki/Special:Redirect/file/Powerful_Strike.png"
    ),

    "club fighting" -> ImbueInfo(
      "🔨 Club Fighting 🔨",
      "Zwiększa umiejętność walki bronią obuchową.\n\n" +
        "**Koszt:**\n" +
        "• 25 Elven Scouting Glasses\n" +
        "• 20 Elven Hoofs\n" +
        "• 10 Metal Spikes",
      "https://tibia.fandom.com/wiki/Special:Redirect/file/Powerful_Bash.png"
    ),

    "axe fighting" -> ImbueInfo(
      "🪓 Axe Fighting 🪓",
      "Zwiększa umiejętność walki toporem.\n\n" +
        "**Koszt:**\n" +
        "• 20 Moohtant Horns\n" +
        "• 25 Battle Stones\n" +
        "• 20 Moohtant Horns",
      "https://tibia.fandom.com/wiki/Special:Redirect/file/Powerful_Chop.png"
    ),

    "sword fighting" -> ImbueInfo(
      "⚔️ Sword Fighting ⚔️",
      "Zwiększa umiejętność walki mieczem.\n\n" +
        "**Koszt:**\n" +
        "• 25 Sabreteeth\n" +
        "• 20 Moohtant Horns\n" +
        "• 5 Lion's Mane",
      "https://tibia.fandom.com/wiki/Special:Redirect/file/Powerful_Slash.png"
    ),

    "shielding" -> ImbueInfo(
      "🛡 Shielding 🛡️",
      "Podnosi umiejętność obrony tarczą o **4**.\n\n" +
        "**Koszt:**\n" +
        "• 20 Pieces of Scarab Shell\n" +
        "• 25 Brimstone Shells\n" +
        "• 25 Frazzle Skins",
      "https://tibia.fandom.com/wiki/Special:Redirect/file/Powerful_Blockade.png"
    ),

    "fist fighting" -> ImbueInfo(
      "🤜 Fist Fighting 🤜",
      "Zwiększa umiejętność walki z łapy.\n\n" +
        "**Koszt:**\n" +
        "• 25 Tarantula Egg\n" +
        "• 20 Mantassin Tails\n" +
        "• 15 Gold-Brocaded Cloth",
      "https://tibia.fandom.com/wiki/Special:Redirect/file/Powerful_Punch.png"
    ),

    // PROTECTIONS
    "paralysis deflection" -> ImbueInfo(
      "🌀 Paralysis Deflection 🛡️",
      "Zmniejsza efekt paraliżu.\n\n" +
        "**Koszt:**\n" +
        "• 20 Wereboar Hooves\n" +
        "• 15 Crystallized Anger\n" +
        "• 5 Quills",
      "https://tibia.fandom.com/wiki/Special:Redirect/file/Powerful_Vibrancy.png"
    ),

    "walking speed" -> ImbueInfo(
      "🏃 Walking Speed 🏃‍♂️",
      "Zwiększa szybkość poruszania się.\n\n" +
        "**Koszt:**\n" +
        "• 15 Damselfly Wings\n" +
        "• 25 Compass Legs\n" +
        "• 20 Waspoid Wings",
      "https://tibia.fandom.com/wiki/Special:Redirect/file/Powerful_Swiftness.png"
    ),

    "death protection" -> ImbueInfo(
      "💀 Death Protection 🛡️",
      "Redukuje otrzymane obrażenia od śmierci o **10%**.\n\n" +
        "**Koszt:**\n" +
        "• 25 Flasks of Embalming Fluid\n" +
        "• 20 Gloom Wolf Furs\n" +
        "• 5 Mystical Hourglasses",
      "https://tibia.fandom.com/wiki/Special:Redirect/file/Powerful_Lich_Shroud.png"
    ),

    "fire protection" -> ImbueInfo(
      "🔥 Fire Protection 🛡️",
      "Redukuje otrzymane obrażenia od ognia o **15%**.\n\n" +
        "**Koszt:**\n" +
        "• 20 Green Dragon Leathers\n" +
        "• 10 Blazing Bones\n" +
        "• 5 Draken Sulphur",
      "https://tibia.fandom.com/wiki/Special:Redirect/file/Powerful_Dragon_Hide.png"
    ),

    "earth protection" -> ImbueInfo(
      "🌿 Earth Protection 🛡️",
      "Redukuje otrzymane obrażenia od ziemi o **15%**.\n\n" +
        "**Koszt:**\n" +
        "• 25 Pieces of Swampling Wood\n" +
        "• 20 Snake Skins\n" +
        "• 10 Brimstone Fangs",
      "https://tibia.fandom.com/wiki/Special:Redirect/file/Powerful_Snake_Skin.png"
    ),

    "ice protection" -> ImbueInfo(
      "❄️ Ice Protection 🛡️",
      "Redukuje otrzymane obrażenia od lodu o **15%**.\n\n" +
        "**Koszt:**\n" +
        "• 25 Winter Wolf Furs\n" +
        "• 15 Thick Furs\n" +
        "• 10 Deepling Warts",
      "https://tibia.fandom.com/wiki/Special:Redirect/file/Powerful_Quara_Scale.png"
    ),

    "energy protection" -> ImbueInfo(
      "⚡ Energy Protection 🛡️",
      "Redukuje otrzymane obrażenia od energii o **15%**.\n\n" +
        "**Koszt:**\n" +
        "• 20 Wyvern Talismans\n" +
        "• 15 Crawler Head Platings\n" +
        "• 10 Wyrm Scales",
      "https://tibia.fandom.com/wiki/Special:Redirect/file/Powerful_Cloud_Fabric.png"
    ),

    "holy protection" -> ImbueInfo(
      "💫 Holy Protection 🛡️",
      "Redukuje otrzymane obrażenia od świętości o **15%**.\n\n" +
        "**Koszt:**\n" +
        "• 25 Cultish Robes\n" +
        "• 25 Cultish Masks\n" +
        "• 20 Hellspawn Tails",
      "https://tibia.fandom.com/wiki/Special:Redirect/file/Powerful_Demon_Presence.png"
    )
  )
}
