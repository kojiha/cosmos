package com.mesosphere.cosmos.handler

import com.mesosphere.Generators.Implicits._
import com.mesosphere.cosmos.HttpClient.ResponseData
import com.mesosphere.cosmos.error.CosmosException
import com.mesosphere.cosmos.error.GenericHttpError
import com.mesosphere.cosmos.error.ResourceTooLarge
import com.mesosphere.cosmos.http.MediaType
import com.netaporter.uri.Uri
import com.twitter.conversions.storage._
import com.twitter.finagle.http.Response
import com.twitter.finagle.http.Status
import com.twitter.util.Await
import com.twitter.util.Future
import com.twitter.util.StorageUnit
import io.finch.Output
import java.io.ByteArrayInputStream
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.scalatest.Assertion
import org.scalatest.FreeSpec
import org.scalatest.prop.PropertyChecks
import scala.collection.mutable

final class ResourceProxyHandlerSpec extends FreeSpec with PropertyChecks {

  type TestData = (StorageUnit, mutable.WrappedArray[Byte], Uri, MediaType)

  "When Content-Length is provided by the upstream server" - {

    "When Content-Length matches the actual content stream length" - {

      "Succeeds if Content-Length is below the limit" in {
        val maxLengthLimit = 100
        val genTestData: Gen[TestData] = for {
          lengthLimit <- Gen.chooseNum(2, maxLengthLimit)
          actualLength <- Gen.chooseNum(1, lengthLimit - 1)
          contentBytes <- Gen.containerOfN[Array, Byte](actualLength, arbitrary[Byte])
          uri <- arbitrary[Uri]
          contentType <- arbitrary[MediaType]
        } yield {
          (lengthLimit.bytes, contentBytes, uri, contentType)
        }

        forAll (genTestData) { case (lengthLimit, contentBytes, uri, contentType) =>
          whenever(contentBytes.length < lengthLimit.bytes) {
            val contentStream = new ByteArrayInputStream(contentBytes.array)
            val responseData = ResponseData(contentType, Some(contentBytes.array.size.toLong), contentStream)
            val bytesRead = ResourceProxyHandler.getContentBytes(uri, responseData, lengthLimit)
            assertResult(contentBytes.array)(bytesRead)
          }
        }
      }

      "Fails if Content-Length is" - {
        val maxLengthLimit = 100
        val genTestData: Gen[TestData] = for {
          lengthLimit <- Gen.chooseNum(2, maxLengthLimit)
          actualLength <- Gen.chooseNum(1, lengthLimit - 1)
          contentBytes <- Gen.containerOfN[Array, Byte](actualLength, arbitrary[Byte])
          uri <- arbitrary[Uri]
          contentType <- arbitrary[MediaType]
        } yield {
          (lengthLimit.bytes, contentBytes, uri, contentType)
        }

        "at the limit" in {
          forAll (genTestData) { case (lengthLimit, contentBytes, uri, contentType) =>
            whenever(contentBytes.length < lengthLimit.bytes) {
              val contentStream = new ByteArrayInputStream(contentBytes.array)
              val responseData = ResponseData(contentType, Some(lengthLimit.bytes), contentStream)
              val exception = intercept[CosmosException](ResourceProxyHandler.getContentBytes(uri, responseData, lengthLimit))
              assert(exception.error.isInstanceOf[ResourceTooLarge])
            }
          }
        }

        "is above the limit" in {
          forAll (genTestData) { case (lengthLimit, contentBytes, uri, contentType) =>
            whenever(contentBytes.length < lengthLimit.bytes) {
              val contentStream = new ByteArrayInputStream(contentBytes.array)
              val responseData = ResponseData(contentType, Some(lengthLimit.bytes + 1), contentStream)
              val exception = intercept[CosmosException](ResourceProxyHandler.getContentBytes(uri, responseData, lengthLimit))
              assert(exception.error.isInstanceOf[ResourceTooLarge])
            }
          }
        }

        "is zero" in {
          forAll (genTestData) { case (lengthLimit, contentBytes, uri, contentType) =>
            whenever(contentBytes.length < lengthLimit.bytes) {
              val contentStream = new ByteArrayInputStream(contentBytes.array)
              val responseData = ResponseData(contentType, Some(0), contentStream)
              val exception = intercept[CosmosException](ResourceProxyHandler.getContentBytes(uri, responseData, lengthLimit))
              assert(exception.error.isInstanceOf[GenericHttpError])
            }
          }
        }
      }

    }

    "When Content-Length does not match the actual content stream length" - {
      // TODO proxy Test cases
      // If ContentLength *was* specified, and the stream is > that, fail
      // If ContentLength *was* specified, and the stream is <= that, pass
      //   - In this case our response content length should be the size of the received data
      "If ContentLength was specified, and the header value is less than actual stream, fail" in {
        val maxLengthLimit = 100
        val genTestData: Gen[TestData] = for {
          lengthLimit <- Gen.chooseNum(1, maxLengthLimit)
          contentBytes <- Gen.containerOfN[Array, Byte](lengthLimit, arbitrary[Byte])
          uri <- arbitrary[Uri]
          contentType <- arbitrary[MediaType]
        } yield {
          (lengthLimit.bytes, contentBytes, uri, contentType)
        }

        forAll (genTestData) { case (lengthLimit, contentBytes, uri, contentType) =>
          val contentStream = new ByteArrayInputStream(contentBytes.array)
          val responseData = ResponseData(contentType, Some((contentBytes.size - 1).toLong), contentStream)
          val ex = intercept[CosmosException](ResourceProxyHandler.getContentBytes(uri, responseData, lengthLimit))
          assert(ex.error.isInstanceOf[GenericHttpError])
          assertResult(Status.InternalServerError)(ex.error.asInstanceOf[GenericHttpError].clientStatus)
        }
      }

      "If ContentLength was specified, and the header value is greater than actual stream, fail" in {
        val maxLengthLimit = 100
        val genTestData: Gen[TestData] = for {
          lengthLimit <- Gen.chooseNum(3, maxLengthLimit)
          contentBytes <- Gen.containerOfN[Array, Byte](lengthLimit-2, arbitrary[Byte])
          uri <- arbitrary[Uri]
          contentType <- arbitrary[MediaType]
        } yield {
          (lengthLimit.bytes, contentBytes, uri, contentType)
        }

        forAll (genTestData) { case (lengthLimit, contentBytes, uri, contentType) =>
          val contentStream = new ByteArrayInputStream(contentBytes.array)
          val responseData = ResponseData(contentType, Some((contentBytes.size + 1).toLong), contentStream)
          val ex = intercept[CosmosException](ResourceProxyHandler.getContentBytes(uri, responseData, lengthLimit))
          assert(ex.error.isInstanceOf[GenericHttpError])
          assertResult(Status.InternalServerError)(ex.error.asInstanceOf[GenericHttpError].clientStatus)
        }
      }

    }

  }

  "When Content-Length is not provided by the upstream server" - {

    "Fails if the content stream length is >= the limit" in {
      val maxLengthLimit = 100
      val maxExcessLength = 20
      val genTestData: Gen[TestData] = for {
        lengthLimit <- Gen.chooseNum(1, maxLengthLimit)
        excessLength <- Gen.chooseNum(0, maxExcessLength)
        contentBytes <- Gen.containerOfN[Array, Byte](lengthLimit + excessLength, arbitrary[Byte])
        uri <- arbitrary[Uri]
        contentType <- arbitrary[MediaType]
      } yield {
        (lengthLimit.bytes, contentBytes, uri, contentType)
      }

      forAll (genTestData) { case (lengthLimit, contentBytes, uri, contentType) =>
        whenever(contentBytes.length >= lengthLimit.bytes) {
          val contentStream = new ByteArrayInputStream(contentBytes.array)
          val responseData = ResponseData(contentType, None, contentStream)
          val exception = intercept[CosmosException](ResourceProxyHandler.getContentBytes(uri, responseData, lengthLimit))
          assert(exception.error.isInstanceOf[ResourceTooLarge])
        }
      }
    }

    "Succeeds if the content stream length is < the limit" in {
      val maxLengthLimit = 100
      val genTestData: Gen[TestData] = for {
        lengthLimit <- Gen.chooseNum(1, maxLengthLimit)
        actualLength <- Gen.chooseNum(0, lengthLimit - 1)
        contentBytes <- Gen.containerOfN[Array, Byte](actualLength, arbitrary[Byte])
        uri <- arbitrary[Uri]
        contentType <- arbitrary[MediaType]
      } yield {
        (lengthLimit.bytes, contentBytes, uri, contentType)
      }

      forAll (genTestData) { case (lengthLimit, contentBytes, uri, contentType) =>
        whenever(contentBytes.length < lengthLimit.bytes) {
          val contentStream = new ByteArrayInputStream(contentBytes.array)
          val responseData = ResponseData(contentType, None, contentStream)
          val bytesRead = ResourceProxyHandler.getContentBytes(uri, responseData, lengthLimit)
          assertResult(contentBytes.array)(bytesRead)
        }
      }
    }

  }

  def assertFailure(output: Future[Output[Response]]): Assertion = {
    val exception = intercept[CosmosException](Await.result(output))

    assertResult(Status.Forbidden)(exception.status)
    assert(exception.error.isInstanceOf[ResourceTooLarge])
  }
}
