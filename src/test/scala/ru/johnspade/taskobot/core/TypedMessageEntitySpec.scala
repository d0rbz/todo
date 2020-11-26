package ru.johnspade.taskobot.core

import ru.johnspade.taskobot.core.TypedMessageEntity._
import telegramium.bots.MessageEntity
import zio.test._
import zio.test.environment.TestEnvironment
import zio.test.Assertion._

object TypedMessageEntitySpec extends DefaultRunnableSpec {
  override def spec: ZSpec[TestEnvironment, Throwable] = suite("TypedMessageEntitySpec")(
    suite("convertation") (
      test("toMessageEntities should convert all contained entities") {
        val stringMessageEntities = List(Bold("bold"), Plain("plain"), Italic("italic"))
        val expected = List(MessageEntity("bold", 0, 4), MessageEntity("italic", 9, 6))
        assert(TypedMessageEntity.toMessageEntities(stringMessageEntities))(hasSameElements(expected))
      }
    ),
    suite("interpolation") (
      test("plain") {
        assert(plain"1${1 + 1}3")(equalTo(Plain("123")))
      },
      test("bold") {
        assert(bold"1${1 + 1}3")(equalTo(Bold("123")))
      },
      test("italic") {
        assert(italic"1${1 + 1}3")(equalTo(Italic("123")))
      }
    )
  )
}
