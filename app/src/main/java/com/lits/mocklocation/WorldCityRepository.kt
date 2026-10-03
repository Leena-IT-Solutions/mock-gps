package com.lits.mocklocation

data class WorldCity(
    val name: String,
    val country: String,
    val flag: String,
    val lat: Double,
    val lng: Double,
    val description: String = ""
)

object WorldCityRepository {
    const val INTERVAL_SECONDS = 120 // 2 minutes per city jump

    val CITIES = listOf(
        WorldCity("Tokyo", "Japan", "🇯🇵", 35.6762, 139.6503, "Capital of Japan • Shinjuku"),
        WorldCity("Paris", "France", "🇫🇷", 48.8566, 2.3522, "City of Light • Eiffel Tower"),
        WorldCity("New York City", "United States", "🇺🇸", 40.7128, -74.0060, "Times Square • Manhattan"),
        WorldCity("London", "United Kingdom", "🇬🇧", 51.5074, -0.1278, "Big Ben • Westminster"),
        WorldCity("Dubai", "United Arab Emirates", "🇦🇪", 25.2048, 55.2708, "Burj Khalifa • Downtown"),
        WorldCity("Singapore", "Singapore", "🇸🇬", 1.3521, 103.8198, "Marina Bay Sands"),
        WorldCity("Sydney", "Australia", "🇦🇺", -33.8688, 151.2093, "Sydney Opera House"),
        WorldCity("Rome", "Italy", "🇮🇹", 41.9028, 12.4964, "Colosseum • Historic Center"),
        WorldCity("Berlin", "Germany", "🇩🇪", 52.5200, 13.4050, "Brandenburg Gate"),
        WorldCity("Mumbai", "India", "🇮🇳", 19.0760, 72.8777, "Gateway of India • Marine Drive"),
        WorldCity("Toronto", "Canada", "🇨🇦", 43.6532, -79.3832, "CN Tower • Downtown"),
        WorldCity("Rio de Janeiro", "Brazil", "🇧🇷", -22.9068, -43.1729, "Copacabana • Christ the Redeemer"),
        WorldCity("Cairo", "Egypt", "🇪🇬", 30.0444, 31.2357, "Nile River • Giza Pyramids"),
        WorldCity("Bangkok", "Thailand", "🇹🇭", 13.7563, 100.5018, "Grand Palace • Chao Phraya"),
        WorldCity("Seoul", "South Korea", "🇰🇷", 37.5665, 126.9780, "Gangnam • Gyeongbokgung"),
        WorldCity("Amsterdam", "Netherlands", "🇳🇱", 52.3676, 4.9041, "Canal Ring • Dam Square"),
        WorldCity("San Francisco", "United States", "🇺🇸", 37.7749, -122.4194, "Golden Gate • Silicon Valley"),
        WorldCity("Los Angeles", "United States", "🇺🇸", 34.0522, -118.2437, "Hollywood • Beverly Hills"),
        WorldCity("Cape Town", "South Africa", "🇿🇦", -33.9249, 18.4241, "Table Mountain • Waterfront"),
        WorldCity("Zurich", "Switzerland", "🇨🇭", 47.3769, 8.5417, "Old Town • Lake Zurich"),
        WorldCity("Barcelona", "Spain", "🇪🇸", 41.3851, 2.1734, "Sagrada Familia • Catalonia"),
        WorldCity("Hong Kong", "China", "🇭🇰", 22.3193, 114.1694, "Victoria Harbour"),
        WorldCity("Istanbul", "Turkey", "🇹🇷", 41.0082, 28.9784, "Bosphorus • Hagia Sophia"),
        WorldCity("Buenos Aires", "Argentina", "🇦🇷", -34.6037, -58.3816, "Plaza de Mayo • La Boca"),
        WorldCity("Vienna", "Austria", "🇦🇹", 48.2082, 16.3738, "Imperial Palaces • Danube"),
        WorldCity("Kuala Lumpur", "Malaysia", "🇲🇾", 3.1390, 101.6869, "Petronas Twin Towers"),
        WorldCity("Madrid", "Spain", "🇪🇸", 40.4168, -3.7038, "Gran Vía • Puerta del Sol"),
        WorldCity("Delhi", "India", "🇮🇳", 28.6139, 77.2090, "India Gate • Connaught Place"),
        WorldCity("Stockholm", "Sweden", "🇸🇪", 59.3293, 18.0686, "Gamla Stan • Baltic Sea"),
        WorldCity("Auckland", "New Zealand", "🇳🇿", -36.8485, 174.7633, "Sky Tower • Waitemata"),
        WorldCity("Mexico City", "Mexico", "🇲🇽", 19.4326, -99.1332, "Zocalo • Reforma"),
        WorldCity("Nairobi", "Kenya", "🇰🇪", -1.2921, 36.8219, "Nairobi National Park"),
        WorldCity("Chicago", "United States", "🇺🇸", 41.8781, -87.6298, "Millennium Park • The Loop"),
        WorldCity("Jakarta", "Indonesia", "🇮🇩", -6.2088, 106.8456, "National Monument • Merdeka"),
        WorldCity("Doha", "Qatar", "🇶🇦", 25.2854, 51.5310, "Corniche • West Bay"),
        WorldCity("Athens", "Greece", "🇬🇷", 37.9838, 23.7275, "Acropolis • Parthenon"),
        WorldCity("Prague", "Czech Republic", "🇨🇿", 50.0755, 14.4378, "Charles Bridge • Old Town Square"),
        WorldCity("Dublin", "Ireland", "🇮🇪", 53.3498, -6.2603, "Temple Bar • River Liffey"),
        WorldCity("Miami", "United States", "🇺🇸", 25.7617, -80.1918, "South Beach • Biscayne Bay"),
        WorldCity("Vancouver", "Canada", "🇨🇦", 49.2827, -123.1207, "Stanley Park • English Bay"),
        WorldCity("Melbourne", "Australia", "🇦🇺", -37.8136, 144.9631, "Yarra River • Federation Square"),
        WorldCity("Santiago", "Chile", "🇨🇱", -33.4489, -70.6693, "Andes Mountains • Plaza de Armas"),
        WorldCity("São Paulo", "Brazil", "🇧🇷", -23.5505, -46.6333, "Paulista Avenue"),
        WorldCity("Marrakech", "Morocco", "🇲🇦", 31.6295, -7.9811, "Jemaa el-Fnaa • Medina")
    )

    var currentCityIndex = 0
    var secondsRemaining = INTERVAL_SECONDS
    var isRandomActive = false

    @Synchronized
    fun getCurrentCity(): WorldCity {
        val idx = currentCityIndex.coerceIn(0, CITIES.size - 1)
        return CITIES[idx]
    }

    @Synchronized
    fun nextCity(): WorldCity {
        currentCityIndex = (currentCityIndex + 1) % CITIES.size
        secondsRemaining = INTERVAL_SECONDS
        return getCurrentCity()
    }

    @Synchronized
    fun setCityByIndex(index: Int): WorldCity {
        currentCityIndex = index.coerceIn(0, CITIES.size - 1)
        secondsRemaining = INTERVAL_SECONDS
        return getCurrentCity()
    }
}
