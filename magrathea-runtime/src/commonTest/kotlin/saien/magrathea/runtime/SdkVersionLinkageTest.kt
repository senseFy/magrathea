package saien.magrathea.runtime

import kotlin.test.Test
import kotlin.test.assertTrue
import saien.magrathea.core.MagratheaSdk

class SdkVersionLinkageTest {
    @Test
    fun downstreamBinariesResolveTheSdkVersion() {
        assertTrue(MagratheaSdk.version.isNotBlank())
    }
}
