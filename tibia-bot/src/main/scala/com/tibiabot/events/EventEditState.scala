package com.tibiabot.events

import java.sql.Timestamp

case class EventEditState(
  eventId:     Int,
  title:       String,
  description: Option[String],
  eventTime:   Timestamp
)
