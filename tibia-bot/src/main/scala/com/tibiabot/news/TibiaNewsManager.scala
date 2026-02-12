package com.tibiabot.news

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.unmarshalling.Unmarshal
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.JDA
import spray.json._
import java.awt.Color
import java.io.{File, PrintWriter}
import scala.concurrent.{ExecutionContext, Future, Await}
import scala.concurrent.duration._
import scala.io.Source
import scala.util.{Failure, Success, Try}

case class TibiaNewsItem(
  id: Int,
  date: String,
  news: String,
  category: String,
  `type`: String,
  url: String,
  url_api: String
)

case class TibiaNewsDetailItem(
  id: Int,
  date: String,
  title: String,
  category: String,
  url: String,
  content: String,
  content_html: String
)

case class TibiaNewsResponse(news: List[TibiaNewsItem])
case class TibiaNewsDetailResponse(news: TibiaNewsDetailItem)

object TibiaNewsJsonProtocol extends DefaultJsonProtocol {
  implicit val newsItemFormat: RootJsonFormat[TibiaNewsItem] = jsonFormat7(TibiaNewsItem)
  implicit val newsResponseFormat: RootJsonFormat[TibiaNewsResponse] = jsonFormat1(TibiaNewsResponse)
  implicit val newsDetailItemFormat: RootJsonFormat[TibiaNewsDetailItem] = jsonFormat7(TibiaNewsDetailItem)
  implicit val newsDetailResponseFormat: RootJsonFormat[TibiaNewsDetailResponse] = jsonFormat1(TibiaNewsDetailResponse)
}

class TibiaNewsManager(implicit system: ActorSystem, ec: ExecutionContext) extends StrictLogging {
  
  import TibiaNewsJsonProtocol._
  
  private val LAST_NEWS_FILE = "last_news_id.txt"
  private val API_URL = "https://api.tibiadata.com/v4/news/latest"
  
  private def getLastNewsId(): Option[Int] = {
    Try {
      val file = new File(LAST_NEWS_FILE)
      if (file.exists()) {
        val source = Source.fromFile(file)
        val id = source.getLines().nextOption().map(_.toInt)
        source.close()
        id
      } else {
        None
      }
    }.toOption.flatten
  }
  
  private def saveLastNewsId(newsId: Int): Unit = {
    Try {
      val writer = new PrintWriter(new File(LAST_NEWS_FILE))
      writer.write(newsId.toString)
      writer.close()
    } match {
      case Success(_) => logger.debug(s"Saved last news ID: $newsId")
      case Failure(e) => logger.error(s"Failed to save last news ID: $newsId", e)
    }
  }
  
  def fetchNews(): Future[List[TibiaNewsItem]] = {
    Http().singleRequest(HttpRequest(uri = API_URL)).flatMap {
      case HttpResponse(status, _, entity, _) if status.isSuccess() =>
        Unmarshal(entity).to[String].map { jsonString =>
          val json = jsonString.parseJson
          val newsResponse = json.convertTo[TibiaNewsResponse]
          newsResponse.news
        }
      case HttpResponse(status, _, entity, _) =>
        entity.discardBytes()
        logger.error(s"Failed to fetch Tibia news, status: $status")
        Future.successful(List.empty)
    }.recover {
      case e: Exception =>
        logger.error("Error fetching Tibia news", e)
        List.empty
    }
  }
  
  def fetchNewsDetails(newsId: Int): Future[Option[TibiaNewsDetailItem]] = {
    val detailUrl = s"https://api.tibiadata.com/v4/news/id/$newsId"
    Http().singleRequest(HttpRequest(uri = detailUrl)).flatMap {
      case HttpResponse(status, _, entity, _) if status.isSuccess() =>
        Unmarshal(entity).to[String].map { jsonString =>
          val json = jsonString.parseJson
          val detailResponse = json.convertTo[TibiaNewsDetailResponse]
          Some(detailResponse.news)
        }
      case HttpResponse(_, _, entity, _) =>
        entity.discardBytes()
        Future.successful(None)
    }.recover {
      case _ => None
    }
  }
  
  def checkAndSendNews(jda: JDA, channelId: String): Future[Unit] = {
    if (channelId.isEmpty || channelId == "0") {
      logger.debug("News channel not configured, skipping news check")
      Future.successful(())
    } else {
      fetchNews().map { newsList =>
        if (newsList.isEmpty) {
          logger.debug("No news fetched from API")
        } else {
          val lastId = getLastNewsId()
          val newItems = lastId match {
            case Some(id) => newsList.takeWhile(_.id != id)
            case None => newsList.take(1)
          }

          if (newItems.nonEmpty) {
            Try {
              val channel = jda.getTextChannelById(channelId)
              if (channel != null && channel.canTalk()) {
                newItems.reverse.foreach { newsItem =>
                  val detailsFuture = fetchNewsDetails(newsItem.id)
                  val details = Await.result(detailsFuture, 5.seconds)
                  
                  details match {
                    case Some(detail) =>
                      val embed = createNewsEmbed(detail)
                      channel.sendMessageEmbeds(embed).queue()
                      Thread.sleep(1000)
                    case None =>
                      logger.warn(s"Could not fetch details for news ${newsItem.id}")
                  }
                }
                saveLastNewsId(newsList.head.id)
                logger.info(s"Sent ${newItems.size} new Tibia news to channel $channelId")
              } else {
                logger.warn(s"Cannot send news to channel $channelId - channel not found or no permissions")
              }
            } match {
              case Failure(e) => logger.error("Error sending news to Discord", e)
              case Success(_) => ()
            }
          } else {
            logger.debug("No new news to send")
          }
        }
      }
    }
  }
  
  private def createNewsEmbed(newsItem: TibiaNewsDetailItem): net.dv8tion.jda.api.entities.MessageEmbed = {
    val truncatedContent = if (newsItem.content.length > 501) {
      newsItem.content.take(498) + "..."
    } else {
      newsItem.content
    }
    
    new EmbedBuilder()
      .setTitle(newsItem.title, newsItem.url)
      .setDescription(truncatedContent)
      .setColor(Color.BLUE)
      .setThumbnail("https://static.tibia.com/images/global/general/tibialogo.gif")
      .setFooter(s"Ticket: ${newsItem.id} - ${newsItem.date}", null)
      .build()
  }
}
