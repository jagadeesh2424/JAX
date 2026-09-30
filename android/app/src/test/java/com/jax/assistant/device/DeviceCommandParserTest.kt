package com.jax.assistant.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCommandParserTest {
    @Test
    fun timeAndDateAreDeterministicCommands() {
        assertEquals(
            DeviceCommand.CurrentDateTime(false, true),
            DeviceCommandParser.parse("What time is it?")
        )
        assertEquals(
            DeviceCommand.CurrentDateTime(true, false),
            DeviceCommandParser.parse("What is today's date?")
        )
        assertEquals(
            DeviceCommand.CurrentDateTime(true, true),
            DeviceCommandParser.parse("What time and date is it?")
        )
    }

    @Test
    fun navigationPhrasesExtractOnlyDestination() {
        val inputs = listOf(
            "Navigate to Bangalore Palace",
            "Find directions to Bangalore Palace",
            "Get directions to Bangalore Palace",
            "How do I get to Bangalore Palace?",
            "Show me how to get to Bangalore Palace",
            "Open maps for Bangalore Palace",
            "Open Bangalore Palace on Maps"
        )
        inputs.forEach { input ->
            assertEquals(DeviceCommand.Navigate("Bangalore Palace"), DeviceCommandParser.parse(input))
        }
    }

    @Test
    fun taskLikeRequestsRemainUnclassifiedByDeviceParser() {
        assertTrue(DeviceCommandParser.parse("Remind me to visit Bangalore Palace tomorrow") == null)
        assertTrue(DeviceCommandParser.parse("Create a task to visit Bangalore Palace") == null)
        assertTrue(DeviceCommandParser.parse("Remind me to call John tomorrow") == null)
    }

    @Test
    fun weatherUsesExternalDataSearch() {
        assertEquals(DeviceCommand.Weather(null), DeviceCommandParser.parse("What's the weather?"))
        assertEquals(DeviceCommand.Weather("Bangalore"), DeviceCommandParser.parse("What's the weather in Bangalore?"))
        assertEquals(DeviceCommand.Weather(null), DeviceCommandParser.parse("Will it rain today?"))
    }

    @Test
    fun generalQuestionsRemainForGemini() {
        assertEquals(null, DeviceCommandParser.parse("Explain SAP FI in simple terms"))
        assertEquals(null, DeviceCommandParser.parse("What is accounts payable?"))
        assertEquals(null, DeviceCommandParser.parse("Explain the difference between AP and AR"))
    }
}
