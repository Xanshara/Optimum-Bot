package com.tibiabot.rashid

/**
 * Model danych dla lokalizacji Rashida
 */
case class RashidLocation(
  dayOfWeek: Int,
  location: String,
  imageUrl: String,
  description: String,
  mapImageUrl: String
)

/**
 * Manager lokalizacji Rashida dla każdego dnia tygodnia
 */
object RashidData {
  
  val locations: Map[Int, RashidLocation] = Map(
    0 -> RashidLocation( // Poniedziałek
      dayOfWeek = 0,
      location = "Svargrond",
      imageUrl = "https://tibiapal.com/images/Rashid.gif",
      description = "On Mondays you can find him in Svargrond in Dankwart's tavern, south of the temple.",
      mapImageUrl = "https://media.discordapp.net/attachments/1305195718754963506/1306947189884325898/1_Rashid_lunes_svarg.gif"
    ),
    1 -> RashidLocation( // Wtorek
      dayOfWeek = 1,
      location = "Liberty Bay",
      imageUrl = "https://tibiapal.com/images/Rashid.gif",
      description = "On Tuesdays you can find him in Liberty Bay in Lyonel's tavern, west of the depot.",
      mapImageUrl = "https://media.discordapp.net/attachments/1305195718754963506/1306947190534705172/2_Rashid_martes_LB.gif"
    ),
    2 -> RashidLocation( // Środa
      dayOfWeek = 2,
      location = "Port Hope",
      imageUrl = "https://tibiapal.com/images/Rashid.gif",
      description = "On Wednesdays you can find him in Port Hope in Clyde's tavern, west of the depot.",
      mapImageUrl = "https://media.discordapp.net/attachments/1305195718754963506/1306947191427956778/3_Rashid_miercoles_PH.gif"
    ),
    3 -> RashidLocation( // Czwartek
      dayOfWeek = 3,
      location = "Ankrahmun",
      imageUrl = "https://tibiapal.com/images/Rashid.gif",
      description = "On Thursdays you can find him in Ankrahmun in Arito's tavern, above the post office.",
      mapImageUrl = "https://media.discordapp.net/attachments/1305195718754963506/1306947191964696686/4_Rashid_jueves_Ank.gif"
    ),
    4 -> RashidLocation( // Piątek
      dayOfWeek = 4,
      location = "Darashia",
      imageUrl = "https://tibiapal.com/images/Rashid.gif",
      description = "On Fridays you can find him in Darashia in Miraia's tavern, south of the guildhalls.",
      mapImageUrl = "https://media.discordapp.net/attachments/1305195718754963506/1306947192652697663/5_Rashid__viernes_dara.gif"
    ),
    5 -> RashidLocation( // Sobota
      dayOfWeek = 5,
      location = "Edron",
      imageUrl = "https://tibiapal.com/images/Rashid.gif",
      description = "On Saturdays you can find him in Edron in Mirabell's tavern, above the depot.",
      mapImageUrl = "https://media.discordapp.net/attachments/1305195718754963506/1306947193118130246/6_Rashid__sabado_edron.gif"
    ),
    6 -> RashidLocation( // Niedziela
      dayOfWeek = 6,
      location = "Carlin",
      imageUrl = "https://tibiapal.com/images/Rashid.gif",
      description = "On Sundays you can find him in Carlin depot one floor above.",
      mapImageUrl = "https://media.discordapp.net/attachments/1305195718754963506/1306947193630097518/7_Rashid__domingo_carlin.gif"
    )
  )
  
  /**
   * Pobiera lokalizację Rashida dla danego dnia tygodnia
   * @param dayOfWeek 0 = Poniedziałek, 6 = Niedziela
   */
  def getLocation(dayOfWeek: Int): Option[RashidLocation] = {
    locations.get(dayOfWeek)
  }
  
  /**
   * Pobiera dzisiejszą lokalizację Rashida
   */
  def getTodayLocation(): Option[RashidLocation] = {
    val calendar = java.util.Calendar.getInstance()
    val dayOfWeek = calendar.get(java.util.Calendar.DAY_OF_WEEK) - 2 // Java: Sunday=1, Monday=2
    val adjustedDay = if (dayOfWeek == -1) 6 else dayOfWeek // Niedziela -> 6
    getLocation(adjustedDay)
  }
}
