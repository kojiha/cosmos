package com.mesosphere.cosmos.converter

import com.mesosphere.cosmos.converter.Response._
import com.mesosphere.cosmos.error.ServiceMarathonTemplateNotFound
import com.mesosphere.cosmos.rpc
import com.mesosphere.cosmos.thirdparty.marathon.model.AppId
import com.mesosphere.universe
import com.mesosphere.universe.bijection.UniverseConversions._
import com.mesosphere.universe.v3.model.Cli
import com.twitter.bijection.Conversion.asMethod
import com.twitter.util.Return
import com.twitter.util.Throw
import com.twitter.util.Try
import org.scalacheck.Gen
import org.scalatest.FreeSpec
import org.scalatest.Matchers
import org.scalatest.prop.GeneratorDrivenPropertyChecks.forAll

final class ResponseSpec extends FreeSpec with Matchers {
  "Conversion[rpc.v2.model.InstallResponse,Try[rpc.v1.model.InstallResponse]]" - {
    val vstring = "9.87.654.3210"
    val ver = universe.v3.model.Version(vstring)
    val name = "ResponseSpec"
    val appid = AppId("foobar")
    val clis = List(None, Some("post install notes"))
    val notes = List(None, Some(Cli(None)))
    val validV2s = for {
      n <- Gen.oneOf(clis)
      c <- Gen.oneOf(notes)
    } yield (rpc.v2.model.InstallResponse(name, ver, Some(appid), n, c))
    val invalidV2s = for {
      n <- Gen.oneOf(clis)
      c <- Gen.oneOf(notes)
    } yield (rpc.v2.model.InstallResponse(name, ver, None, n, c))


    "success" in {
      val v1 = rpc.v1.model.InstallResponse(name, ver.as[universe.v2.model.PackageDetailsVersion], appid)

      forAll(validV2s) { x =>
        x.as[Try[rpc.v1.model.InstallResponse]] shouldBe Return(v1)
      }
    }
    "failure" in {
      //expecting failure due to missing marathon mustache
      forAll(invalidV2s) { x =>
        x.as[Try[rpc.v1.model.InstallResponse]] shouldBe Throw(
          ServiceMarathonTemplateNotFound(name, ver).exception
        )
      }
    }
  }

}
