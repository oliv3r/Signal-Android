package org.thoughtcrime.securesms.testing

import com.google.protobuf.ByteString
import org.thoughtcrime.securesms.database.model.toProtoByteString
import org.thoughtcrime.securesms.groups.GroupId
import org.thoughtcrime.securesms.messages.SignalServiceProtoUtil.buildWith
import org.thoughtcrime.securesms.messages.TestMessage
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.whispersystems.signalservice.api.crypto.EnvelopeMetadata
import org.whispersystems.signalservice.api.util.UuidUtil
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.AttachmentPointer
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Content
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.DataMessage
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Envelope
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.GroupContextV2
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.SyncMessage
import java.util.UUID
import kotlin.random.Random
import kotlin.random.nextInt
import kotlin.time.Duration.Companion.days

/**
 * Random but deterministic fuzzer for create various message content protos.
 */
object MessageContentFuzzer {

  private val mediaTypes = listOf("image/png", "image/jpeg", "image/heic", "image/heif", "image/avif", "image/webp", "image/gif", "audio/aac", "audio/*", "video/mp4", "video/*", "text/x-vcard", "text/x-signal-plain", "application/x-signal-view-once", "*/*", "application/octet-stream")
  private val emojis = listOf("😂", "❤️", "🔥", "😍", "👀", "🤔", "🙏", "👍", "🤷", "🥺")

  private val random = Random(1)

  /**
   * Create an [Envelope].
   */
  fun envelope(timestamp: Long): Envelope {
    return Envelope.newBuilder()
      .setTimestamp(timestamp)
      .setServerTimestamp(timestamp + 5)
      .setServerGuidBytes(UuidUtil.toByteString(UUID.randomUUID()))
      .build()
  }

  /**
   * Create metadata to match an [Envelope].
   */
  fun envelopeMetadata(source: RecipientId, destination: RecipientId, groupId: GroupId.V2? = null): EnvelopeMetadata {
    return EnvelopeMetadata(
      sourceServiceId = Recipient.resolved(source).requireServiceId(),
      sourceE164 = null,
      sourceDeviceId = 1,
      sealedSender = true,
      groupId = groupId?.decodedId,
      destinationServiceId = Recipient.resolved(destination).requireServiceId()
    )
  }

  /**
   * Create a random text message that will contain a body but may also contain
   * - An expire timer value
   * - Bold style body ranges
   */
  fun fuzzTextMessage(groupContextV2: GroupContextV2? = null): Content {
    return Content.newBuilder()
      .setDataMessage(
        DataMessage.newBuilder().buildWith {
          body = string()
          if (random.nextBoolean()) {
            expireTimer = random.nextInt(0..28.days.inWholeSeconds.toInt())
          }
          if (random.nextBoolean()) {
            addBodyRanges(
              SignalServiceProtos.BodyRange.newBuilder().buildWith {
                start = 0
                length = 1
                style = SignalServiceProtos.BodyRange.Style.BOLD
              }
            )
          }
          if (groupContextV2 != null) {
            groupV2 = groupContextV2
          }
        }
      )
      .build()
  }

  /**
   * Create a sync sent text message for the given [DataMessage].
   */
  fun syncSentTextMessage(
    textMessage: DataMessage,
    deliveredTo: List<RecipientId>,
    recipientUpdate: Boolean = false
  ): Content {
    return Content
      .newBuilder()
      .setSyncMessage(
        SyncMessage.newBuilder().buildWith {
          sent = SyncMessage.Sent.newBuilder().buildWith {
            timestamp = textMessage.timestamp
            message = textMessage
            isRecipientUpdate = recipientUpdate
            addAllUnidentifiedStatus(
              deliveredTo.map {
                SyncMessage.Sent.UnidentifiedDeliveryStatus.newBuilder().buildWith {
                  destinationServiceId = Recipient.resolved(it).requireServiceId().toString()
                  unidentified = true
                }
              }
            )
          }
        }
      ).build()
  }

  /**
   * Create a random media message that may be:
   * - A text body
   * - A text body with a quote that references an existing message
   * - A text body with a quote that references a non existing message
   * - A message with 0-2 attachment pointers and may contain a text body
   */
  fun fuzzMediaMessageWithBody(quoteAble: List<TestMessage> = emptyList()): Content {
    return Content.newBuilder()
      .setDataMessage(
        DataMessage.newBuilder().buildWith {
          if (random.nextBoolean()) {
            body = string()
          }

          if (random.nextBoolean() && quoteAble.isNotEmpty()) {
            body = string()
            val quoted = quoteAble.random(random)
            quote = DataMessage.Quote.newBuilder().buildWith {
              id = quoted.envelope.timestamp
              authorAci = quoted.metadata.sourceServiceId.toString()
              text = quoted.content.dataMessage.body
              addAllAttachments(quoted.content.dataMessage.attachmentsList)
              addAllBodyRanges(quoted.content.dataMessage.bodyRangesList)
              type = DataMessage.Quote.Type.NORMAL
            }
          }

          if (random.nextFloat() < 0.1 && quoteAble.isNotEmpty()) {
            val quoted = quoteAble.random(random)
            quote = DataMessage.Quote.newBuilder().buildWith {
              id = random.nextLong(quoted.envelope.timestamp - 1000000, quoted.envelope.timestamp)
              authorAci = quoted.metadata.sourceServiceId.toString()
              text = quoted.content.dataMessage.body
            }
          }

          if (random.nextFloat() < 0.25) {
            val total = random.nextInt(1, 2)
            (0..total).forEach { _ -> addAttachments(attachmentPointer()) }
          }
        }
      )
      .build()
  }

  /**
   * Creates a random media message that contains no traditional media content. It may be:
   * - A reaction to a prior message
   */
  fun fuzzMediaMessageNoContent(previousMessages: List<TestMessage> = emptyList()): Content {
    return Content.newBuilder()
      .setDataMessage(
        DataMessage.newBuilder().buildWith {
          if (random.nextFloat() < 0.25) {
            val reactTo = previousMessages.random(random)
            reaction = DataMessage.Reaction.newBuilder().buildWith {
              emoji = emojis.random(random)
              remove = false
              targetAuthorAci = reactTo.metadata.sourceServiceId.toString()
              targetSentTimestamp = reactTo.envelope.timestamp
            }
          }
        }
      ).build()
  }

  /**
   * Create a random media message that can never contain a text body. It may be:
   * - A sticker
   */
  fun fuzzMediaMessageNoText(previousMessages: List<TestMessage> = emptyList()): Content {
    return Content.newBuilder()
      .setDataMessage(
        DataMessage.newBuilder().buildWith {
          if (random.nextFloat() < 0.9) {
            sticker = DataMessage.Sticker.newBuilder().buildWith {
              packId = byteString(length = 24)
              packKey = byteString(length = 128)
              stickerId = random.nextInt()
              data = attachmentPointer()
              emoji = emojis.random(random)
            }
          }
        }
      ).build()
  }

  /**
   * Generate a random [String].
   */
  fun string(length: Int = 10, allowNullString: Boolean = false): String {
    var string = ""

    if (allowNullString && random.nextBoolean()) {
      return string
    }

    for (i in 0 until length) {
      string += random.nextInt(65..90).toChar()
    }
    return string
  }

  /**
   * Generate a random [ByteString].
   */
  fun byteString(length: Int = 512): ByteString {
    return random.nextBytes(length).toProtoByteString()
  }

  /**
   * Generate a random [AttachmentPointer].
   */
  fun attachmentPointer(): AttachmentPointer {
    return AttachmentPointer.newBuilder().run {
      cdnKey = string()
      contentType = mediaTypes.random(random)
      key = byteString()
      size = random.nextInt(1024 * 1024 * 50)
      thumbnail = byteString()
      digest = byteString()
      fileName = string()
      flags = 0
      width = random.nextInt(until = 1024)
      height = random.nextInt(until = 1024)
      caption = string(allowNullString = true)
      blurHash = string()
      uploadTimestamp = random.nextLong()
      cdnNumber = 1

      build()
    }
  }

  /**
   * Creates a server delivered timestamp that is always later than the envelope and server "received" timestamp.
   */
  fun fuzzServerDeliveredTimestamp(envelopeTimestamp: Long): Long {
    return envelopeTimestamp + 10
  }
}
