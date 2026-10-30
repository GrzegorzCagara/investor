package pl.investor.api

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import pl.investor.notification.DeviceRepository
import java.time.Clock

@RestController
@RequestMapping("/api/devices")
class DeviceController(private val devices: DeviceRepository, private val clock: Clock) {

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun register(@RequestBody request: DeviceRequest) {
        if (request.token.isBlank()) throw ResponseStatusException(HttpStatus.BAD_REQUEST)
        devices.register(request.token, clock.instant())
    }
}
