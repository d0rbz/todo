package ru.johnspade.taskobot.settings

import cats.syntax.option._
import org.mockito.captor.ArgCaptor
import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import ru.johnspade.taskobot.TestAssertions.isMethodsEqual
import ru.johnspade.taskobot.TestEnvironments
import ru.johnspade.taskobot.TestHelpers.{callbackQuery, mockMessage}
import ru.johnspade.taskobot.TestUsers.{john, johnChatId, johnTg}
import ru.johnspade.taskobot.core.TelegramOps.inlineKeyboardButton
import ru.johnspade.taskobot.core.callbackqueries.{CallbackQueryData, ContextCallbackQuery}
import ru.johnspade.taskobot.core.{ChangeLanguage, SetLanguage}
import ru.johnspade.taskobot.i18n.Language
import ru.johnspade.taskobot.settings.SettingsController.SettingsController
import ru.johnspade.taskobot.user.UserRepository
import ru.johnspade.taskobot.user.UserRepository.UserRepository
import telegramium.bots.client.Method
import telegramium.bots.high._
import telegramium.bots.{ChatIntId, Message}
import zio.blocking.Blocking
import zio.test.Assertion.{equalTo, hasField, isSome}
import zio.test._
import zio.test.environment.TestEnvironment
import zio.{Task, URLayer, ZIO, ZLayer}

object SettingsControllerSpec extends DefaultRunnableSpec with MockitoSugar with ArgumentMatchersSugar {
  def spec: ZSpec[TestEnvironment, Throwable] = suite("SettingsControllerSpec")(
    suite("ChangeLanguage")(
      testM("should list languages") {
        for {
          reply <- sendChangeLanguageQuery()
          listLanguagesAssertions = verifyMethodCalled(Methods.editMessageText(
            ChatIntId(johnChatId).some,
            messageId = 0.some,
            text = "Current language: English",
            replyMarkup = InlineKeyboardMarkup.singleColumn(
              List(
                inlineKeyboardButton("English", SetLanguage(Language.English)),
                inlineKeyboardButton("Русский", SetLanguage(Language.Russian)),
                inlineKeyboardButton("Turkish", SetLanguage(Language.Turkish)),
                inlineKeyboardButton("Italian", SetLanguage(Language.Italian))
              )
            ).some
          ))
          replyAssertions = assert(reply)(isSome(equalTo(Methods.answerCallbackQuery("0"))))
        } yield listLanguagesAssertions && replyAssertions
      }
    ),

    suite("SetLanguage")(
      testM("should change language") {
        for {
          reply <- sendSetLanguageQuery()
          user <- UserRepository.findById(john.id)
          userAssertions = assert(user.get)(hasField("language", _.language, equalTo[Language, Language](Language.Russian)))
          listLanguagesAssertions = verifyMethodCalled(Methods.editMessageText(
            ChatIntId(johnChatId).some,
            messageId = 0.some,
            text = "Текущий язык: Русский",
            replyMarkup = InlineKeyboardMarkup.singleColumn(
              List(
                inlineKeyboardButton("English", SetLanguage(Language.English)),
                inlineKeyboardButton("Русский", SetLanguage(Language.Russian)),
                inlineKeyboardButton("Turkish", SetLanguage(Language.Turkish)),
                inlineKeyboardButton("Italian", SetLanguage(Language.Italian))
              )
            ).some
          ))

          replyAssertions = assert(reply)(isSome(equalTo(Methods.answerCallbackQuery("0", "Язык изменен".some))))
        } yield userAssertions && listLanguagesAssertions && replyAssertions
      }
    )
  )
    .provideCustomLayerShared(TestEnvironment.env)

  private def sendChangeLanguageQuery() =
    ZIO.accessM[SettingsController] {
      _.get
        .userRoutes
        .run(ContextCallbackQuery(
          john, CallbackQueryData(ChangeLanguage, callbackQuery(ChangeLanguage, johnTg, inlineMessageId = "0".some))
        ))
        .value
        .map(_.flatten)
    }

  private def sendSetLanguageQuery() = {
    val cbData = SetLanguage(Language.Russian)
    ZIO.accessM[SettingsController] {
      _.get
        .routes
        .run(CallbackQueryData(cbData, callbackQuery(cbData, johnTg, inlineMessageId = "0".some)))
        .value
        .map(_.flatten)
    }
  }

  private val botApiMock = mock[Api[Task]]
  when(botApiMock.execute[Message](*)).thenReturn(Task.succeed(mockMessage()))
  when(botApiMock.execute[Either[Boolean, Message]](*)).thenReturn(Task.right(mockMessage()))

  private def verifyMethodCalled[Res](method: Method[Res]) = {
    val captor = ArgCaptor[Method[Res]]
    verify(botApiMock, atLeastOnce).execute(captor).asInstanceOf[Unit]
    assert(captor.values)(Assertion.exists(isMethodsEqual(method)))
  }


  private object TestEnvironment {
    private val botApi = ZLayer.succeed(botApiMock)
    private val userRepo = UserRepository.live
    private val settingsController = botApi ++ userRepo >>> SettingsController.live

    val env: URLayer[Blocking, SettingsController with UserRepository] =
      TestEnvironments.itLayer >>> settingsController ++ userRepo
  }
}
