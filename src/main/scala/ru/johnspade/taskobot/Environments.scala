package ru.johnspade.taskobot

import ru.johnspade.taskobot.Configuration.Configuration
import ru.johnspade.taskobot.Taskobot.Taskobot
import ru.johnspade.taskobot.task.TaskRepository
import ru.johnspade.taskobot.user.UserRepository
import zio.{URLayer, ZLayer}
import zio.blocking.Blocking
import zio.clock.Clock

object Environments {
  type AppEnvironment = Blocking with Clock with Configuration with Taskobot

  private val dbConfig = Configuration.liveDbConfig
  private val sessionPool = dbConfig >>> SessionPool.live
  private val repositories = sessionPool >>> (UserRepository.live ++ TaskRepository.live)
  private val botConfig = Configuration.liveBotConfig
  private val botService = repositories >>> BotService.live
  private val botApi = botConfig >>> TelegramBotApi.live
  private val commandController =
    (ZLayer.requires[Clock] ++ botApi ++ botService ++ repositories) >>> CommandController.live
  private val taskobot =
    (ZLayer.requires[Clock] ++ botApi ++ botConfig ++ botService ++ repositories ++ commandController) >>> Taskobot.live

  val appEnvironment: URLayer[Blocking with Clock, AppEnvironment] =
    ZLayer.requires[Blocking with Clock] >+> (botConfig ++ dbConfig ++ taskobot)
}
