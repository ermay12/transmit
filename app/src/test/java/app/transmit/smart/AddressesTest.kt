package app.transmit.smart

import org.junit.Assert.assertEquals
import org.junit.Test

class AddressesTest {
    private fun found(text: String) = Addresses.find(text).map { text.substring(it) }

    @Test
    fun usAndUkAddresses() {
        assertEquals(
            listOf("1600 Amphitheatre Parkway, Mountain View, CA 94043"),
            found("Let's meet at 1600 Amphitheatre Parkway, Mountain View, CA 94043"),
        )
        assertEquals(listOf("500 Castro St, Mountain View"), found("Also the party is Nov 28 from 6-9pm at 500 Castro St, Mountain View"))
        assertEquals(listOf("123 main st"), found("im at 123 main st"))
        assertEquals(listOf("350 5th Ave, New York, NY 10118"), found("350 5th Ave, New York, NY 10118"))
        assertEquals(listOf("1 Infinite Loop, Cupertino"), found("Drop it off at 1 Infinite Loop, Cupertino."))
        assertEquals(listOf("1585 Broadway"), found("It's at 1585 Broadway tonight"))
        assertEquals(listOf("221B Baker Street"), found("221B Baker Street"))
        assertEquals(listOf("742 Evergreen Terrace Apt 4"), found("742 Evergreen Terrace Apt 4, ring twice"))
        assertEquals(listOf("123 Main St"), found("See you at 123 Main St. Bring snacks"))
        assertEquals(listOf("88 Colin P Kelly Jr St"), found("Office is 88 Colin P Kelly Jr St, I'll be in the lobby"))
    }

    @Test
    fun europeanAddresses() {
        assertEquals(listOf("12 rue de Rivoli, 75001 Paris"), found("RDV au 12 rue de Rivoli, 75001 Paris"))
        assertEquals(listOf("Calle de Alcalá 42, Madrid"), found("Estoy en Calle de Alcalá 42, Madrid"))
        assertEquals(listOf("Hauptstraße 5, 10115 Berlin"), found("Wir treffen uns in der Hauptstraße 5, 10115 Berlin"))
        assertEquals(listOf("Via Roma 10"), found("Ci vediamo in Via Roma 10"))
        assertEquals(listOf("Avenida Paulista, 1578"), found("Fica na Avenida Paulista, 1578"))
    }

    @Test
    fun ordinaryPhrasesAreNotAddresses() {
        assertEquals(emptyList<String>(), found("I walked 3 blocks down the road"))
        assertEquals(emptyList<String>(), found("it's a 2 way street"))
        assertEquals(emptyList<String>(), found("took 2 hours on the highway"))
        assertEquals(emptyList<String>(), found("we need 4 more people at the court"))
        assertEquals(emptyList<String>(), found("only 2 left in st"))
        assertEquals(emptyList<String>(), found("Bring 2 bottles"))
        assertEquals(emptyList<String>(), found("sent via Email 2 times"))
        assertEquals(emptyList<String>(), found("Call me at 650-253-0000"))
    }
}
