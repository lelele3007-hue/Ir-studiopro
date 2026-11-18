#include <IRremote.hpp>
#define IR_RECEIVE_PIN 2

void setup() {
  Serial.begin(115200);
  IrReceiver.begin(IR_RECEIVE_PIN, ENABLE_LED_FEEDBACK);
}

void loop() {
  if (IrReceiver.decode()) {
    if (IrReceiver.decodedIRData.rawDataPtr->rawlen > 4) {
      Serial.print("{\"freq\":38000,\"raw\":[");
      for (uint16_t i = 1; i < IrReceiver.decodedIRData.rawDataPtr->rawlen; i++) {
        Serial.print(IrReceiver.decodedIRData.rawDataPtr->rawbuf[i] * MICROS_PER_TICK);
        if (i < IrReceiver.decodedIRData.rawDataPtr->rawlen - 1) Serial.print(",");
      }
      Serial.println("]}");
    }
    IrReceiver.resume();
  }
}
