package com.autonoumus.ai.core.util

import android.content.Context
import android.location.Geocoder
import java.util.Locale

data class LocationSuggestion(
    val primaryTitle: String,
    val subtitle: String,
    val country: String,
    val address: String,
    val latitudeFormatted: String,
    val longitudeFormatted: String,
    val rawLatitude: Double,
    val rawLongitude: Double,
    val isCity: Boolean = true
) {
    override fun toString(): String {
        return primaryTitle
    }
}

data class CountryBounds(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double
) {
    fun contains(lat: Double, lon: Double): Boolean {
        val latOk = lat in minLat..maxLat
        val lonOk = if (minLon <= maxLon) {
            lon in minLon..maxLon
        } else {
            lon >= minLon || lon <= maxLon
        }
        return latOk && lonOk
    }
}

object WorldLocationProvider {

    private val COUNTRY_ALIASES = mapOf(
        "uk" to "United Kingdom",
        "u.k." to "United Kingdom",
        "united kingdom" to "United Kingdom",
        "great britain" to "United Kingdom",
        "britain" to "United Kingdom",
        "england" to "United Kingdom",
        "scotland" to "United Kingdom",
        "wales" to "United Kingdom",
        "northern ireland" to "United Kingdom",
        "gb" to "United Kingdom",
        "gbr" to "United Kingdom",

        "usa" to "United States",
        "u.s.a." to "United States",
        "us" to "United States",
        "u.s." to "United States",
        "united states" to "United States",
        "united states of america" to "United States",
        "america" to "United States",

        "uae" to "United Arab Emirates",
        "u.a.e." to "United Arab Emirates",
        "united arab emirates" to "United Arab Emirates",
        "emirates" to "United Arab Emirates",
        "dubai" to "United Arab Emirates",
        "abu dhabi" to "United Arab Emirates",

        "ksa" to "Saudi Arabia",
        "k.s.a." to "Saudi Arabia",
        "saudi" to "Saudi Arabia",
        "saudi arabia" to "Saudi Arabia",
        "kingdom of saudi arabia" to "Saudi Arabia",

        "pk" to "Pakistan",
        "pak" to "Pakistan",
        "pakistan" to "Pakistan",

        "in" to "India",
        "ind" to "India",
        "india" to "India",
        "bharat" to "India",

        "jp" to "Japan",
        "jpn" to "Japan",
        "japan" to "Japan",
        "nippon" to "Japan",

        "ca" to "Canada",
        "can" to "Canada",
        "canada" to "Canada",

        "au" to "Australia",
        "aus" to "Australia",
        "australia" to "Australia",

        "de" to "Germany",
        "deu" to "Germany",
        "germany" to "Germany",
        "deutschland" to "Germany",

        "fr" to "France",
        "fra" to "France",
        "france" to "France",

        "it" to "Italy",
        "ita" to "Italy",
        "italy" to "Italy",
        "italia" to "Italy",

        "es" to "Spain",
        "esp" to "Spain",
        "spain" to "Spain",
        "españa" to "Spain",

        "kr" to "South Korea",
        "kor" to "South Korea",
        "south korea" to "South Korea",
        "korea" to "South Korea",

        "cn" to "China",
        "chn" to "China",
        "china" to "China",

        "sg" to "Singapore",
        "sgp" to "Singapore",
        "singapore" to "Singapore",

        "tr" to "Turkey",
        "tur" to "Turkey",
        "turkey" to "Turkey",
        "türkiye" to "Turkey",

        "eg" to "Egypt",
        "egy" to "Egypt",
        "egypt" to "Egypt",

        "br" to "Brazil",
        "bra" to "Brazil",
        "brazil" to "Brazil",
        "brasil" to "Brazil",

        "nl" to "Netherlands",
        "nld" to "Netherlands",
        "netherlands" to "Netherlands",
        "holland" to "Netherlands",

        "ch" to "Switzerland",
        "che" to "Switzerland",
        "switzerland" to "Switzerland",
        "swiss" to "Switzerland",

        "th" to "Thailand",
        "tha" to "Thailand",
        "thailand" to "Thailand",

        "id" to "Indonesia",
        "idn" to "Indonesia",
        "indonesia" to "Indonesia",

        "my" to "Malaysia",
        "mys" to "Malaysia",
        "malaysia" to "Malaysia",

        "za" to "South Africa",
        "zaf" to "South Africa",
        "south africa" to "South Africa",

        "mx" to "Mexico",
        "mex" to "Mexico",
        "mexico" to "Mexico",

        "gr" to "Greece",
        "grc" to "Greece",
        "greece" to "Greece",

        "pt" to "Portugal",
        "prt" to "Portugal",
        "portugal" to "Portugal",

        "nz" to "New Zealand",
        "nzl" to "New Zealand",
        "new zealand" to "New Zealand",

        "ie" to "Ireland",
        "irl" to "Ireland",
        "ireland" to "Ireland",

        "at" to "Austria",
        "aut" to "Austria",
        "austria" to "Austria",

        "se" to "Sweden",
        "swe" to "Sweden",
        "sweden" to "Sweden",

        "no" to "Norway",
        "nor" to "Norway",
        "norway" to "Norway",

        "qa" to "Qatar",
        "qat" to "Qatar",
        "qatar" to "Qatar"
    )

    fun normalizeCountry(countryName: String?): String {
        if (countryName.isNullOrBlank()) return ""
        val trimmed = countryName.trim()
        val lower = trimmed.lowercase(Locale.ROOT)
        val cleaned = lower.replace(Regex("[^a-z0-9 ]"), "").trim()
        return COUNTRY_ALIASES[cleaned] ?: COUNTRY_ALIASES[lower] ?: trimmed
    }

    fun isCountryMatch(sourceCountry: String?, targetCountry: String?): Boolean {
        if (sourceCountry.isNullOrBlank() || targetCountry.isNullOrBlank()) return false
        val normSource = normalizeCountry(sourceCountry)
        val normTarget = normalizeCountry(targetCountry)

        if (normSource.equals(normTarget, ignoreCase = true)) return true

        val s = sourceCountry.trim().lowercase(Locale.ROOT)
        val t = targetCountry.trim().lowercase(Locale.ROOT)
        if (s == t) return true

        val sClean = s.replace(Regex("[^a-z0-9 ]"), "")
        val tClean = t.replace(Regex("[^a-z0-9 ]"), "")
        if (sClean == tClean) return true

        // For word-based prefixes, only match if clean string is >= 4 characters to prevent 2-letter false matches
        if (tClean.length >= 4 && (sClean.startsWith(tClean) || sClean.endsWith(tClean) || sClean == tClean)) return true
        if (sClean.length >= 4 && (tClean.startsWith(sClean) || tClean.endsWith(sClean) || tClean == sClean)) return true

        return false
    }

    val FAMOUS_CITIES = listOf(
        // United Kingdom
        LocationSuggestion("London, Westminster", "Westminster, Parliament Square, London", "United Kingdom", "Westminster, Parliament Square, London", "51.4995° N", "0.1248° W", 51.4995, -0.1248, true),
        LocationSuggestion("London, Tower Bridge", "Tower Bridge Rd, London SE1 2UP", "United Kingdom", "Tower Bridge Rd, London SE1 2UP", "51.5055° N", "0.0754° W", 51.5055, -0.0754, true),
        LocationSuggestion("London, Covent Garden", "The Market, Covent Garden, London WC2E 8RF", "United Kingdom", "The Market, Covent Garden, London WC2E 8RF", "51.5117° N", "0.1240° W", 51.5117, -0.1240, true),
        LocationSuggestion("London, Piccadilly Circus", "Piccadilly Circus, London W1D 7ET", "United Kingdom", "Piccadilly Circus, London W1D 7ET", "51.5101° N", "0.1340° W", 51.5101, -0.1340, true),
        LocationSuggestion("Manchester, Deansgate", "Deansgate, Manchester M3 2FW", "United Kingdom", "Deansgate, Manchester M3 2FW", "53.4808° N", "2.2426° W", 53.4808, -2.2426, true),
        LocationSuggestion("Manchester, Old Trafford", "Sir Matt Busby Way, Stretford, Manchester M16 0RA", "United Kingdom", "Sir Matt Busby Way, Stretford, Manchester M16 0RA", "53.4631° N", "2.2913° W", 53.4631, -2.2913, true),
        LocationSuggestion("Birmingham, City Centre", "New Street, Birmingham B2 4NS", "United Kingdom", "New Street, Birmingham B2 4NS", "52.4862° N", "1.8904° W", 52.4862, -1.8904, true),
        LocationSuggestion("Birmingham, Bullring", "Moor Street Queensway, Birmingham B5 4BU", "United Kingdom", "Moor Street Queensway, Birmingham B5 4BU", "52.4776° N", "1.8936° W", 52.4776, -1.8936, true),
        LocationSuggestion("Edinburgh, Royal Mile", "High St, Edinburgh EH1 1RE", "United Kingdom", "High St, Edinburgh EH1 1RE", "55.9505° N", "3.1883° W", 55.9505, -3.1883, true),
        LocationSuggestion("Edinburgh, Princes Street", "Princes St, Edinburgh EH2 2ER", "United Kingdom", "Princes St, Edinburgh EH2 2ER", "55.9520° N", "3.1970° W", 55.9520, -3.1970, true),
        LocationSuggestion("Glasgow, George Square", "George Square, Glasgow G2 1DH", "United Kingdom", "George Square, Glasgow G2 1DH", "55.8612° N", "4.2518° W", 55.8612, -4.2518, true),
        LocationSuggestion("Liverpool, Royal Albert Dock", "Albert Dock, Liverpool L3 4AQ", "United Kingdom", "Albert Dock, Liverpool L3 4AQ", "53.4013° N", "2.9930° W", 53.4013, -2.9930, true),
        LocationSuggestion("Bristol, Harbourside", "Canons Way, Bristol BS1 5UH", "United Kingdom", "Canons Way, Bristol BS1 5UH", "51.4502° N", "2.5973° W", 51.4502, -2.5973, true),
        LocationSuggestion("Leeds, City Centre", "Briggate, Leeds LS1 6HD", "United Kingdom", "Briggate, Leeds LS1 6HD", "53.7969° N", "1.5427° W", 53.7969, -1.5427, true),
        LocationSuggestion("Belfast, Titanic Quarter", "Queen's Rd, Titanic Quarter, Belfast BT3 9DT", "United Kingdom", "Queen's Rd, Titanic Quarter, Belfast BT3 9DT", "54.6080° N", "5.9100° W", 54.6080, -5.9100, true),
        LocationSuggestion("Oxford, High Street", "High St, Oxford OX1 4AH", "United Kingdom", "High St, Oxford OX1 4AH", "51.7520° N", "1.2577° W", 51.7520, -1.2577, true),
        LocationSuggestion("Cambridge, King's Parade", "King's Parade, Cambridge CB2 1ST", "United Kingdom", "King's Parade, Cambridge CB2 1ST", "52.2043° N", "0.1171° E", 52.2043, 0.1171, true),
        LocationSuggestion("Cardiff, Castle Quarter", "Castle St, Cardiff CF10 3RB", "United Kingdom", "Castle St, Cardiff CF10 3RB", "51.4816° N", "3.1812° W", 51.4816, -3.1812, true),
        LocationSuggestion("Newcastle, Quayside", "Sandhill, Newcastle upon Tyne NE1 3AF", "United Kingdom", "Sandhill, Newcastle upon Tyne NE1 3AF", "54.9691° N", "1.6062° W", 54.9691, -1.6062, true),

        // United States
        LocationSuggestion("Miami Beach, FL", "Miami Beach, Ocean Drive, FL", "United States", "Miami Beach, Ocean Drive, FL", "25.7617° N", "80.1918° W", 25.7617, -80.1918, true),
        LocationSuggestion("New York, Times Square", "Broadway & 7th Ave, Manhattan, NY", "United States", "Broadway & 7th Ave, Manhattan, NY", "40.7580° N", "73.9855° W", 40.7580, -73.9855, true),
        LocationSuggestion("Los Angeles, Hollywood", "Hollywood Blvd, Los Angeles, CA", "United States", "Hollywood Blvd, Los Angeles, CA", "34.1016° N", "118.3268° W", 34.1016, -118.3268, true),
        LocationSuggestion("San Francisco, Market St", "Market St, Financial District, CA", "United States", "Market St, Financial District, CA", "37.7749° N", "122.4194° W", 37.7749, -122.4194, true),
        LocationSuggestion("Chicago, Michigan Ave", "North Michigan Ave, Magnificent Mile, IL", "United States", "North Michigan Ave, Magnificent Mile, IL", "41.8923° N", "87.6244° W", 41.8923, -87.6244, true),
        LocationSuggestion("Las Vegas, The Strip", "Las Vegas Blvd S, Las Vegas, NV", "United States", "Las Vegas Blvd S, Las Vegas, NV", "36.1147° N", "115.1728° W", 36.1147, -115.1728, true),
        LocationSuggestion("Honolulu, Waikiki Beach", "Kalakaua Ave, Honolulu, HI", "United States", "Kalakaua Ave, Honolulu, HI", "21.2766° N", "157.8275° W", 21.2766, -157.8275, true),
        LocationSuggestion("Seattle, Pike Place", "Pike Pl, Seattle, WA", "United States", "Pike Pl, Seattle, WA", "47.6085° N", "122.3401° W", 47.6085, -122.3401, true),
        LocationSuggestion("Washington, D.C.", "Pennsylvania Ave NW, Washington, DC", "United States", "Pennsylvania Ave NW, Washington, DC", "38.8977° N", "77.0365° W", 38.8977, -77.0365, true),

        // Pakistan
        LocationSuggestion("Islamabad, Sector F-7", "Street 4, Sector F-7/3, Islamabad", "Pakistan", "Street 4, Sector F-7/3, Islamabad", "33.7294° N", "73.0551° E", 33.7294, 73.0551, true),
        LocationSuggestion("Lahore, Gulberg", "Main Boulevard Gulberg, Lahore", "Pakistan", "Main Boulevard Gulberg, Lahore", "31.5204° N", "74.3587° E", 31.5204, 74.3587, true),
        LocationSuggestion("Karachi, Clifton", "Block 4 Clifton, Karachi, Sindh", "Pakistan", "Block 4 Clifton, Karachi, Sindh", "24.8138° N", "67.0300° E", 24.8138, 67.0300, true),
        LocationSuggestion("Rawalpindi, Saddar", "The Mall Road, Saddar, Rawalpindi", "Pakistan", "The Mall Road, Saddar, Rawalpindi", "33.5973° N", "73.0479° E", 33.5973, 73.0479, true),
        LocationSuggestion("Faisalabad, Clock Tower", "Ghanta Ghar, Faisalabad, Punjab", "Pakistan", "Ghanta Ghar, Faisalabad, Punjab", "31.4187° N", "73.0791° E", 31.4187, 73.0791, true),
        LocationSuggestion("Multan, Cantt", "Mall Road, Multan Cantt, Punjab", "Pakistan", "Mall Road, Multan Cantt, Punjab", "30.1984° N", "71.4687° E", 30.1984, 71.4687, true),
        LocationSuggestion("Peshawar, University Town", "Jamrud Road, University Town, Peshawar", "Pakistan", "Jamrud Road, University Town, Peshawar", "34.0080° N", "71.4870° E", 34.0080, 71.4870, true),
        LocationSuggestion("Chishtian, Punjab", "Main Bazar, Chishtian, Bahawalnagar", "Pakistan", "Main Bazar, Chishtian, Bahawalnagar", "29.7958° N", "72.8578° E", 29.7958, 72.8578, true),
        LocationSuggestion("Bahawalpur, Noor Mahal", "Noor Mahal Road, Bahawalpur, Punjab", "Pakistan", "Noor Mahal Road, Bahawalpur, Punjab", "29.3879° N", "71.6914° E", 29.3879, 71.6914, true),

        // Japan
        LocationSuggestion("Tokyo, Shibuya", "Tokyo, Shibuya Crossing, Japan", "Japan", "Tokyo, Shibuya Crossing, Japan", "35.6595° N", "139.7004° E", 35.6595, 139.7004, true),
        LocationSuggestion("Tokyo, Shinjuku", "Shinjuku City, Tokyo", "Japan", "Shinjuku City, Tokyo", "35.6938° N", "139.7034° E", 35.6938, 139.7034, true),
        LocationSuggestion("Kyoto, Gion", "Gionmachi Minamigawa, Higashiyama, Kyoto", "Japan", "Gionmachi Minamigawa, Higashiyama, Kyoto", "35.0037° N", "135.7772° E", 35.0037, 135.7772, true),
        LocationSuggestion("Osaka, Dotonbori", "Dotonbori, Chuo Ward, Osaka", "Japan", "Dotonbori, Chuo Ward, Osaka", "34.6687° N", "135.5013° E", 34.6687, 135.5013, true),
        LocationSuggestion("Yokohama, Minato Mirai", "Minatomirai, Nishi Ward, Yokohama", "Japan", "Minatomirai, Nishi Ward, Yokohama", "35.4578° N", "139.6324° E", 35.4578, 139.6324, true),

        // France
        LocationSuggestion("Paris, Eiffel Tower", "Champ de Mars, 5 Av. Anatole, Paris", "France", "Champ de Mars, 5 Av. Anatole, Paris", "48.8584° N", "2.2945° E", 48.8584, 2.2945, true),
        LocationSuggestion("Paris, Louvre", "Rue de Rivoli, 75001 Paris", "France", "Rue de Rivoli, 75001 Paris", "48.8606° N", "2.3376° E", 48.8606, 2.3376, true),
        LocationSuggestion("Nice, Promenade", "Promenade des Anglais, 06000 Nice", "France", "Promenade des Anglais, 06000 Nice", "43.6959° N", "7.2562° E", 43.6959, 7.2562, true),
        LocationSuggestion("Lyon, Bellecour", "Place Bellecour, 69002 Lyon", "France", "Place Bellecour, 69002 Lyon", "45.7578° N", "4.8320° E", 45.7578, 4.8320, true),
        LocationSuggestion("Marseille, Vieux-Port", "Quai du Port, 13002 Marseille", "France", "Quai du Port, 13002 Marseille", "43.2965° N", "5.3698° E", 43.2965, 5.3698, true),

        // Germany
        LocationSuggestion("Berlin, Alexanderplatz", "Alexanderplatz, 10178 Berlin", "Germany", "Alexanderplatz, 10178 Berlin", "52.5219° N", "13.4132° E", 52.5219, 13.4132, true),
        LocationSuggestion("Munich, Marienplatz", "Marienplatz, 80331 München", "Germany", "Marienplatz, 80331 München", "48.1374° N", "11.5755° E", 48.1374, 11.5755, true),
        LocationSuggestion("Frankfurt, Romerberg", "Römerberg 27, 60311 Frankfurt", "Germany", "Römerberg 27, 60311 Frankfurt", "50.1109° N", "8.6821° E", 50.1109, 8.6821, true),
        LocationSuggestion("Hamburg, Speicherstadt", "Speicherstadt, 20457 Hamburg", "Germany", "Speicherstadt, 20457 Hamburg", "53.5453° N", "9.9922° E", 53.5453, 9.9922, true),

        // Italy
        LocationSuggestion("Rome, Colosseum", "Piazza del Colosseo, 1, 00184 Roma", "Italy", "Piazza del Colosseo, 1, 00184 Roma", "41.8902° N", "12.4922° E", 41.8902, 12.4922, true),
        LocationSuggestion("Milan, Duomo", "Piazza del Duomo, 20122 Milano", "Italy", "Piazza del Duomo, 20122 Milano", "45.4642° N", "9.1900° E", 45.4642, 9.1900, true),
        LocationSuggestion("Venice, St. Mark's Square", "Piazza San Marco, 30124 Venezia", "Italy", "Piazza San Marco, 30124 Venezia", "45.4342° N", "12.3389° E", 45.4342, 12.3389, true),
        LocationSuggestion("Florence, Duomo", "Piazza del Duomo, 50122 Firenze", "Italy", "Piazza del Duomo, 50122 Firenze", "43.7731° N", "11.2560° E", 43.7731, 11.2560, true),

        // Spain
        LocationSuggestion("Madrid, Gran Vía", "Gran Vía, Centro, 28013 Madrid", "Spain", "Gran Vía, Centro, 28013 Madrid", "40.4203° N", "3.7058° W", 40.4203, -3.7058, true),
        LocationSuggestion("Barcelona, Sagrada Familia", "Carrer de Mallorca, 401, Barcelona", "Spain", "Carrer de Mallorca, 401, Barcelona", "41.4036° N", "2.1744° E", 41.4036, 2.1744, true),
        LocationSuggestion("Seville, Plaza de España", "Plaza de España, 41013 Sevilla", "Spain", "Plaza de España, 41013 Sevilla", "37.3772° N", "5.9869° W", 37.3772, -5.9869, true),

        // United Arab Emirates
        LocationSuggestion("Dubai, Burj Khalifa", "1 Sheikh Mohammed bin Rashid Blvd, Dubai", "United Arab Emirates", "1 Sheikh Mohammed bin Rashid Blvd, Dubai", "25.1972° N", "55.2744° E", 25.1972, 55.2744, true),
        LocationSuggestion("Dubai, Marina", "Dubai Marina Walk, Dubai", "United Arab Emirates", "Dubai Marina Walk, Dubai", "25.0805° N", "55.1403° E", 25.0805, 55.1403, true),
        LocationSuggestion("Abu Dhabi, Corniche", "Corniche Rd, Abu Dhabi", "United Arab Emirates", "Corniche Rd, Abu Dhabi", "24.4697° N", "54.3414° E", 24.4697, 54.3414, true),

        // Saudi Arabia
        LocationSuggestion("Riyadh, Olaya", "King Fahd Rd, Al Olaya, Riyadh", "Saudi Arabia", "King Fahd Rd, Al Olaya, Riyadh", "24.6937° N", "46.6853° E", 24.6937, 46.6853, true),
        LocationSuggestion("Jeddah, Corniche", "Jeddah Corniche Rd, Jeddah", "Saudi Arabia", "Jeddah Corniche Rd, Jeddah", "21.5433° N", "39.1728° E", 21.5433, 39.1728, true),
        LocationSuggestion("Mecca, Haram", "Al Haram, Mecca 24231", "Saudi Arabia", "Al Haram, Mecca 24231", "21.4225° N", "39.8262° E", 21.4225, 39.8262, true),

        // Canada
        LocationSuggestion("Toronto, CN Tower", "290 Bremner Blvd, Toronto, ON", "Canada", "290 Bremner Blvd, Toronto, ON", "43.6426° N", "79.3871° W", 43.6426, -79.3871, true),
        LocationSuggestion("Vancouver, Gastown", "Water St, Gastown, Vancouver, BC", "Canada", "Water St, Gastown, Vancouver, BC", "49.2838° N", "123.1093° W", 49.2838, -123.1093, true),
        LocationSuggestion("Montreal, Old Port", "Rue de la Commune, Montréal, QC", "Canada", "Rue de la Commune, Montréal, QC", "45.5048° N", "73.5525° W", 45.5048, -73.5525, true),

        // Australia
        LocationSuggestion("Sydney, Opera House", "Bennelong Point, Sydney NSW 2000", "Australia", "Bennelong Point, Sydney NSW 2000", "33.8568° S", "151.2153° E", -33.8568, 151.2153, true),
        LocationSuggestion("Melbourne, Federation Square", "Swanston St & Flinders St, Melbourne", "Australia", "Swanston St & Flinders St, Melbourne", "37.8180° S", "144.9671° E", -37.8180, 144.9671, true),
        LocationSuggestion("Brisbane, South Bank", "South Bank, Brisbane QLD 4101", "Australia", "South Bank, Brisbane QLD 4101", "27.4772° S", "153.0193° E", -27.4772, 153.0193, true),

        // India
        LocationSuggestion("Mumbai, Marine Drive", "Netaji Subhash Chandra Bose Rd, Mumbai", "India", "Netaji Subhash Chandra Bose Rd, Mumbai", "18.9432° N", "72.8230° E", 18.9432, 72.8230, true),
        LocationSuggestion("New Delhi, Connaught Place", "Connaught Place, New Delhi, Delhi", "India", "Connaught Place, New Delhi, Delhi", "28.6304° N", "77.2177° E", 28.6304, 77.2177, true),
        LocationSuggestion("Bengaluru, MG Road", "Mahatma Gandhi Rd, Bengaluru, Karnataka", "India", "Mahatma Gandhi Rd, Bengaluru, Karnataka", "12.9756° N", "77.6066° E", 12.9756, 77.6066, true),

        // Singapore
        LocationSuggestion("Singapore, Marina Bay", "10 Bayfront Ave, Marina Bay Sands", "Singapore", "10 Bayfront Ave, Marina Bay Sands", "1.2838° N", "103.8591° E", 1.2838, 103.8591, true),
        LocationSuggestion("Singapore, Orchard Road", "Orchard Rd, Singapore", "Singapore", "Orchard Rd, Singapore", "1.3048° N", "103.8318° E", 1.3048, 103.8318, true),

        // South Korea
        LocationSuggestion("Seoul, Gangnam", "Teheran-ro, Gangnam-gu, Seoul", "South Korea", "Teheran-ro, Gangnam-gu, Seoul", "37.4979° N", "127.0276° E", 37.4979, 127.0276, true),
        LocationSuggestion("Busan, Haeundae", "Haeundae Beach Rd, Busan", "South Korea", "Haeundae Beach Rd, Busan", "35.1587° N", "129.1604° E", 35.1587, 129.1604, true),

        // China
        LocationSuggestion("Beijing, Forbidden City", "4 Jingshan Front St, Dongcheng, Beijing", "China", "4 Jingshan Front St, Dongcheng, Beijing", "39.9163° N", "116.3972° E", 39.9163, 116.3972, true),
        LocationSuggestion("Shanghai, The Bund", "Zhongshan East 1st Rd, Huangpu, Shanghai", "China", "Zhongshan East 1st Rd, Huangpu, Shanghai", "31.2400° N", "121.4900° E", 31.2400, 121.4900, true),
        LocationSuggestion("Hong Kong, Victoria Peak", "Peak Road, The Peak, Hong Kong", "Hong Kong", "Peak Road, The Peak, Hong Kong", "22.2759° N", "114.1455° E", 22.2759, 114.1455, true),

        // Turkey
        LocationSuggestion("Istanbul, Sultanahmet", "Sultan Ahmet, Fatih, Istanbul", "Turkey", "Sultan Ahmet, Fatih, Istanbul", "41.0054° N", "28.9768° E", 41.0054, 28.9768, true),
        LocationSuggestion("Ankara, Kızılay", "Kızılay, Çankaya, Ankara", "Turkey", "Kızılay, Çankaya, Ankara", "39.9208° N", "32.8541° E", 39.9208, 32.8541, true),

        // Egypt
        LocationSuggestion("Cairo, Tahrir Square", "Tahrir Square, Qasr An Nile, Cairo", "Egypt", "Tahrir Square, Qasr An Nile, Cairo", "30.0444° N", "31.2357° E", 30.0444, 31.2357, true),
        LocationSuggestion("Giza, Pyramids", "Al Haram, Giza Governorate", "Egypt", "Al Haram, Giza Governorate", "29.9792° N", "31.1342° E", 29.9792, 31.1342, true),

        // Brazil
        LocationSuggestion("Rio de Janeiro, Copacabana", "Av. Atlântica, Copacabana, Rio", "Brazil", "Av. Atlântica, Copacabana, Rio", "22.9711° S", "43.1825° W", -22.9711, -43.1825, true),
        LocationSuggestion("Sao Paulo, Paulista", "Av. Paulista, Bela Vista, São Paulo", "Brazil", "Av. Paulista, Bela Vista, São Paulo", "23.5615° S", "46.6559° W", -23.5615, -46.6559, true),

        // Netherlands
        LocationSuggestion("Amsterdam, Canal Ring", "Herengracht 380, 1016 CJ Amsterdam", "Netherlands", "Herengracht 380, 1016 CJ Amsterdam", "52.3676° N", "4.9041° E", 52.3676, 4.9041, true),
        LocationSuggestion("Rotterdam, Erasmus Bridge", "Erasmusbrug, 3011 BN Rotterdam", "Netherlands", "Erasmusbrug, 3011 BN Rotterdam", "51.9090° N", "4.4875° E", 51.9090, 4.4875, true),

        // Switzerland
        LocationSuggestion("Zurich, Bahnhofstrasse", "Bahnhofstrasse, 8001 Zürich", "Switzerland", "Bahnhofstrasse, 8001 Zürich", "47.3739° N", "8.5380° E", 47.3739, 8.5380, true),
        LocationSuggestion("Geneva, Jet d'Eau", "Quai Gustave-Ador, 1207 Genève", "Switzerland", "Quai Gustave-Ador, 1207 Genève", "46.2074° N", "6.1559° E", 46.2074, 6.1559, true),

        // Thailand
        LocationSuggestion("Bangkok, Sukhumvit", "Sukhumvit Rd, Khlong Toei, Bangkok", "Thailand", "Sukhumvit Rd, Khlong Toei, Bangkok", "13.7367° N", "100.5608° E", 13.7367, 100.5608, true),
        LocationSuggestion("Phuket, Patong", "Patong Beach, Kathu District, Phuket", "Thailand", "Patong Beach, Kathu District, Phuket", "7.8964° N", "98.2965° E", 7.8964, 98.2965, true),

        // Indonesia
        LocationSuggestion("Bali, Seminyak Beach", "Jl. Kayu Aya, Seminyak, Bali", "Indonesia", "Jl. Kayu Aya, Seminyak, Bali", "8.6913° S", "115.1557° E", -8.6913, 115.1557, true),
        LocationSuggestion("Jakarta, Thamrin", "Jl. M.H. Thamrin, Jakarta Pusat", "Indonesia", "Jl. M.H. Thamrin, Jakarta Pusat", "6.1944° S", "106.8229° E", -6.1944, 106.8229, true),

        // Malaysia
        LocationSuggestion("Kuala Lumpur, KLCC", "Jalan Ampang, Kuala Lumpur City Centre", "Malaysia", "Jalan Ampang, Kuala Lumpur City Centre", "3.1578° N", "101.7119° E", 3.1578, 101.7119, true),
        LocationSuggestion("Penang, George Town", "Beach St, George Town, Penang", "Malaysia", "Beach St, George Town, Penang", "5.4164° N", "100.3327° E", 5.4164, 100.3327, true),

        // South Africa
        LocationSuggestion("Cape Town, Waterfront", "Dock Rd, Victoria & Alfred Waterfront", "South Africa", "Dock Rd, Victoria & Alfred Waterfront", "33.9036° S", "18.4205° E", -33.9036, 18.4205, true),
        LocationSuggestion("Johannesburg, Sandton", "5th St, Sandton, Johannesburg", "South Africa", "5th St, Sandton, Johannesburg", "26.1076° S", "28.0567° E", -26.1076, 28.0567, true),

        // Mexico
        LocationSuggestion("Mexico City, Reforma", "Paseo de la Reforma, Cuauhtémoc, CDMX", "Mexico", "Paseo de la Reforma, Cuauhtémoc, CDMX", "19.4270° N", "99.1677° W", 19.4270, -99.1677, true),
        LocationSuggestion("Cancun, Hotel Zone", "Blvd. Kukulcan, Zona Hotelera, Cancún", "Mexico", "Blvd. Kukulcan, Zona Hotelera, Cancún", "21.1367° N", "86.7456° W", 21.1367, -86.7456, true),

        // Greece
        LocationSuggestion("Athens, Syntagma", "Pl. Sintagmatos, Athina 105 57", "Greece", "Pl. Sintagmatos, Athina 105 57", "37.9753° N", "23.7361° E", 37.9753, 23.7361, true),
        LocationSuggestion("Santorini, Oia", "Oia 847 02, Santorini", "Greece", "Oia 847 02, Santorini", "36.4618° N", "25.3753° E", 36.4618, 25.3753, true),

        // Portugal
        LocationSuggestion("Lisbon, Belém", "Praça do Império, 1400-206 Lisboa", "Portugal", "Praça do Império, 1400-206 Lisboa", "38.6979° N", "9.2064° W", 38.6979, -9.2064, true),
        LocationSuggestion("Porto, Ribeira", "Praça da Ribeira, 4050-513 Porto", "Portugal", "Praça da Ribeira, 4050-513 Porto", "41.1406° N", "8.6131° W", 41.1406, -8.6131, true),

        // New Zealand
        LocationSuggestion("Auckland, Queen Street", "Queen Street, Auckland CBD", "New Zealand", "Queen Street, Auckland CBD", "36.8509° S", "174.7645° E", -36.8509, 174.7645, true),
        LocationSuggestion("Wellington, Te Aro", "Courtenay Pl, Te Aro, Wellington", "New Zealand", "Courtenay Pl, Te Aro, Wellington", "41.2924° S", "174.7787° E", -41.2924, 174.7787, true),

        // Ireland
        LocationSuggestion("Dublin, Grafton Street", "Grafton Street, Dublin 2", "Ireland", "Grafton Street, Dublin 2", "53.3418° N", "6.2592° W", 53.3418, -6.2592, true),

        // Austria
        LocationSuggestion("Vienna, Stephansplatz", "Stephansplatz, 1010 Wien", "Austria", "Stephansplatz, 1010 Wien", "48.2085° N", "16.3731° E", 48.2085, 16.3731, true),

        // Sweden
        LocationSuggestion("Stockholm, Gamla Stan", "Stortorget, 111 29 Stockholm", "Sweden", "Stortorget, 111 29 Stockholm", "59.3250° N", "18.0708° E", 59.3250, 18.0708, true),

        // Norway
        LocationSuggestion("Oslo, Karl Johans gate", "Karl Johans gate, 0154 Oslo", "Norway", "Karl Johans gate, 0154 Oslo", "59.9139° N", "10.7522° E", 59.9139, 10.7522, true),

        // Qatar
        LocationSuggestion("Doha, Corniche", "Doha Corniche, West Bay, Doha", "Qatar", "Doha Corniche, West Bay, Doha", "25.2929° N", "51.5298° E", 25.2929, 51.5298, true)
    )

    private val allIsoCountries: List<String> by lazy {
        Locale.getISOCountries()
            .map { countryCode ->
                try {
                    Locale.Builder().setRegion(countryCode).build().displayCountry
                } catch (e: Exception) {
                    @Suppress("DEPRECATION")
                    Locale("", countryCode).displayCountry
                }
            }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }

    val COUNTRY_SUGGESTIONS: List<LocationSuggestion> by lazy {
        val list = mutableListOf<LocationSuggestion>()

        // 1. Preset famous regions first
        FAMOUS_CITIES.distinctBy { it.country }.forEach { city ->
            list.add(
                LocationSuggestion(
                    primaryTitle = city.country,
                    subtitle = "${city.primaryTitle} • ${city.latitudeFormatted}, ${city.longitudeFormatted}",
                    country = city.country,
                    address = city.address,
                    latitudeFormatted = city.latitudeFormatted,
                    longitudeFormatted = city.longitudeFormatted,
                    rawLatitude = city.rawLatitude,
                    rawLongitude = city.rawLongitude,
                    isCity = false
                )
            )
        }

        // 2. Add all other world countries
        allIsoCountries.forEach { countryName ->
            if (list.none { it.primaryTitle.equals(countryName, ignoreCase = true) }) {
                list.add(
                    LocationSuggestion(
                        primaryTitle = countryName,
                        subtitle = "Country / Global Territory",
                        country = countryName,
                        address = "$countryName Downtown District",
                        latitudeFormatted = "0.0000° N",
                        longitudeFormatted = "0.0000° E",
                        rawLatitude = 0.0,
                        rawLongitude = 0.0,
                        isCity = false
                    )
                )
            }
        }

        list
    }

    fun searchCountries(query: String): List<LocationSuggestion> {
        val q = query.trim()
        if (q.isEmpty()) return COUNTRY_SUGGESTIONS

        val normalizedTarget = normalizeCountry(q)

        return COUNTRY_SUGGESTIONS.filter { item ->
            isCountryMatch(item.country, q) ||
            item.primaryTitle.contains(q, ignoreCase = true) ||
            item.subtitle.contains(q, ignoreCase = true) ||
            item.country.contains(q, ignoreCase = true)
        }.sortedWith { a, b ->
            val aExact = a.country.equals(normalizedTarget, ignoreCase = true) || a.country.equals(q, ignoreCase = true)
            val bExact = b.country.equals(normalizedTarget, ignoreCase = true) || b.country.equals(q, ignoreCase = true)
            when {
                aExact && !bExact -> -1
                !aExact && bExact -> 1
                a.country.startsWith(q, ignoreCase = true) && !b.country.startsWith(q, ignoreCase = true) -> -1
                !a.country.startsWith(q, ignoreCase = true) && b.country.startsWith(q, ignoreCase = true) -> 1
                else -> 0
            }
        }
    }

    fun searchCities(query: String, countryFilter: String? = null, context: Context? = null): List<LocationSuggestion> {
        val q = query.trim()
        val cFilter = countryFilter?.trim().orEmpty()

        // 1. Filter in-memory preset cities strictly matching the specified country
        val countryMatches = if (cFilter.isNotBlank()) {
            FAMOUS_CITIES.filter { city ->
                isCountryMatch(city.country, cFilter)
            }
        } else {
            FAMOUS_CITIES
        }

        val resultList = mutableListOf<LocationSuggestion>()

        if (q.isNotBlank()) {
            val queryFiltered = countryMatches.filter {
                it.primaryTitle.contains(q, ignoreCase = true) ||
                it.address.contains(q, ignoreCase = true) ||
                it.subtitle.contains(q, ignoreCase = true)
            }
            resultList.addAll(queryFiltered)

            // Complement with Android Geocoder when available for specific street/city searches
            if (context != null) {
                try {
                    val geocoder = Geocoder(context, Locale.getDefault())
                    val searchQuery = if (cFilter.isNotBlank() && !q.contains(cFilter, ignoreCase = true)) {
                        "$q, $cFilter"
                    } else {
                        q
                    }
                    val addresses = geocoder.getFromLocationName(searchQuery, 8)
                    if (!addresses.isNullOrEmpty()) {
                        for (addr in addresses) {
                            val countryName = addr.countryName ?: cFilter

                            // Strict country filter check for Geocoder results
                            if (cFilter.isNotBlank() && !isCountryMatch(countryName, cFilter) && !isCountryMatch(addr.countryCode.orEmpty(), cFilter)) {
                                continue
                            }

                            val cityOrFeature = addr.locality ?: addr.subAdminArea ?: addr.adminArea ?: addr.featureName ?: q
                            val fullAddress = (0..addr.maxAddressLineIndex)
                                .mapNotNull { addr.getAddressLine(it) }
                                .joinToString(", ")
                            val lat = addr.latitude
                            val lon = addr.longitude
                            val latFmt = OsmGeocodingHelper.formatLatitude(lat, decimals = 4)
                            val lonFmt = OsmGeocodingHelper.formatLongitude(lon, decimals = 4)

                            val suggestion = LocationSuggestion(
                                primaryTitle = "$cityOrFeature, $countryName",
                                subtitle = fullAddress.ifBlank { "$cityOrFeature, $countryName" },
                                country = countryName,
                                address = fullAddress.ifBlank { "$cityOrFeature, $countryName" },
                                latitudeFormatted = latFmt,
                                longitudeFormatted = lonFmt,
                                rawLatitude = lat,
                                rawLongitude = lon,
                                isCity = true
                            )
                            if (resultList.none {
                                    it.primaryTitle.equals(suggestion.primaryTitle, ignoreCase = true) ||
                                    it.address.equals(suggestion.address, ignoreCase = true)
                                }) {
                                resultList.add(suggestion)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Ignore Geocoder network exceptions gracefully
                }
            }
        } else {
            resultList.addAll(countryMatches)
        }

        // If no preset matches exist for this country, generate country-specific district suggestions
        if (resultList.isEmpty() && cFilter.isNotBlank()) {
            val countryItem = COUNTRY_SUGGESTIONS.firstOrNull { isCountryMatch(it.country, cFilter) }
            val baseCountry = countryItem?.country ?: normalizeCountry(cFilter).ifBlank { cFilter }
            val lat = countryItem?.rawLatitude ?: 0.0
            val lon = countryItem?.rawLongitude ?: 0.0
            val latFmt = countryItem?.latitudeFormatted ?: "0.0000° N"
            val lonFmt = countryItem?.longitudeFormatted ?: "0.0000° E"

            resultList.add(LocationSuggestion(
                primaryTitle = "$baseCountry Central City",
                subtitle = "Downtown Commercial District, $baseCountry",
                country = baseCountry,
                address = "Downtown Commercial District, $baseCountry",
                latitudeFormatted = latFmt,
                longitudeFormatted = lonFmt,
                rawLatitude = lat,
                rawLongitude = lon,
                isCity = true
            ))
            resultList.add(LocationSuggestion(
                primaryTitle = "$baseCountry North District",
                subtitle = "Northern Province, $baseCountry",
                country = baseCountry,
                address = "Northern Province, $baseCountry",
                latitudeFormatted = latFmt,
                longitudeFormatted = lonFmt,
                rawLatitude = lat,
                rawLongitude = lon,
                isCity = true
            ))
            resultList.add(LocationSuggestion(
                primaryTitle = "$baseCountry Capital Area",
                subtitle = "Capital Administrative Region, $baseCountry",
                country = baseCountry,
                address = "Capital Administrative Region, $baseCountry",
                latitudeFormatted = latFmt,
                longitudeFormatted = lonFmt,
                rawLatitude = lat,
                rawLongitude = lon,
                isCity = true
            ))
        }

        return resultList
    }

    val COUNTRY_BOUNDS = mapOf(
        "United Kingdom" to CountryBounds(49.5, 61.2, -8.9, 2.0),
        "United States" to CountryBounds(18.0, 72.0, -179.9, -66.0),
        "Pakistan" to CountryBounds(23.5, 37.3, 60.8, 77.9),
        "India" to CountryBounds(6.5, 35.7, 68.0, 97.5),
        "Japan" to CountryBounds(24.0, 45.7, 122.5, 154.0),
        "France" to CountryBounds(41.3, 51.2, -5.5, 9.7),
        "Germany" to CountryBounds(47.2, 55.1, 5.8, 15.1),
        "Italy" to CountryBounds(35.4, 47.1, 6.6, 18.6),
        "Spain" to CountryBounds(27.5, 43.8, -18.2, 4.4),
        "United Arab Emirates" to CountryBounds(22.6, 26.2, 51.5, 56.4),
        "Saudi Arabia" to CountryBounds(16.3, 32.2, 34.5, 55.7),
        "Canada" to CountryBounds(41.6, 83.2, -141.0, -52.6),
        "Australia" to CountryBounds(-44.0, -10.0, 112.0, 154.0),
        "Singapore" to CountryBounds(1.15, 1.48, 103.55, 104.1),
        "South Korea" to CountryBounds(33.0, 38.7, 124.5, 131.0),
        "China" to CountryBounds(18.0, 53.6, 73.4, 135.1),
        "Turkey" to CountryBounds(35.8, 42.2, 25.6, 44.9),
        "Egypt" to CountryBounds(21.9, 31.7, 24.6, 37.0),
        "Brazil" to CountryBounds(-33.8, 5.3, -73.9, -34.7),
        "Netherlands" to CountryBounds(50.7, 53.6, 3.3, 7.3),
        "Switzerland" to CountryBounds(45.8, 47.9, 5.9, 10.5),
        "Thailand" to CountryBounds(5.6, 20.5, 97.3, 105.7),
        "Indonesia" to CountryBounds(-11.0, 6.1, 95.0, 141.1),
        "Malaysia" to CountryBounds(0.8, 7.4, 99.5, 119.3),
        "South Africa" to CountryBounds(-34.9, -22.1, 16.4, 33.0),
        "Mexico" to CountryBounds(14.5, 32.8, -118.4, -86.7),
        "Greece" to CountryBounds(34.8, 41.8, 19.3, 29.7),
        "Portugal" to CountryBounds(32.6, 42.2, -31.3, -6.1),
        "New Zealand" to CountryBounds(-47.3, -34.4, 166.4, 178.6),
        "Ireland" to CountryBounds(51.4, 55.4, -10.5, -5.9),
        "Austria" to CountryBounds(46.3, 49.1, 9.5, 17.2),
        "Sweden" to CountryBounds(55.3, 69.1, 11.0, 24.2),
        "Norway" to CountryBounds(57.9, 71.2, 4.5, 31.1),
        "Qatar" to CountryBounds(24.4, 26.2, 50.7, 51.7),
        "Russia" to CountryBounds(41.1, 81.9, 19.6, -168.0),
        "Bangladesh" to CountryBounds(20.5, 26.7, 88.0, 92.7),
        "Sri Lanka" to CountryBounds(5.9, 9.9, 79.6, 81.9),
        "Nepal" to CountryBounds(26.3, 30.5, 80.0, 88.2),
        "Afghanistan" to CountryBounds(29.3, 38.5, 60.5, 74.9),
        "Iran" to CountryBounds(25.0, 39.8, 44.0, 63.4),
        "Iraq" to CountryBounds(29.0, 37.4, 38.7, 48.7),
        "Kuwait" to CountryBounds(28.5, 30.1, 46.5, 48.5),
        "Bahrain" to CountryBounds(25.5, 26.4, 50.3, 50.8),
        "Oman" to CountryBounds(16.6, 26.4, 52.0, 59.9),
        "Jordan" to CountryBounds(29.1, 33.4, 34.9, 39.3),
        "Lebanon" to CountryBounds(33.0, 34.7, 35.1, 36.7),
        "Philippines" to CountryBounds(4.5, 21.2, 116.8, 126.7),
        "Vietnam" to CountryBounds(8.5, 23.4, 102.1, 109.5),
        "Argentina" to CountryBounds(-55.1, -21.7, -73.6, -53.6),
        "Chile" to CountryBounds(-56.0, -17.5, -75.7, -66.8),
        "Colombia" to CountryBounds(-4.3, 13.4, -79.1, -66.8),
        "Peru" to CountryBounds(-18.4, -0.03, -81.4, -68.6),
        "Poland" to CountryBounds(49.0, 54.9, 14.1, 24.2),
        "Belgium" to CountryBounds(49.4, 51.6, 2.5, 6.5),
        "Denmark" to CountryBounds(54.5, 57.8, 8.0, 15.2),
        "Finland" to CountryBounds(59.8, 70.1, 20.5, 31.6),
        "Morocco" to CountryBounds(21.4, 36.0, -17.1, -1.0),
        "Nigeria" to CountryBounds(4.2, 13.9, 2.6, 14.7),
        "Kenya" to CountryBounds(-4.7, 5.1, 33.9, 41.9),
        "Hong Kong" to CountryBounds(22.15, 22.57, 113.83, 114.41),
        "Taiwan" to CountryBounds(21.8, 25.3, 119.9, 122.1),
        "Czech Republic" to CountryBounds(48.5, 51.1, 12.0, 18.9),
        "Hungary" to CountryBounds(45.7, 48.6, 16.1, 22.9),
        "Romania" to CountryBounds(43.6, 48.3, 20.2, 29.7),
        "Croatia" to CountryBounds(42.3, 46.6, 13.4, 19.5),
        "Iceland" to CountryBounds(63.3, 66.6, -24.6, -13.4),
        "Algeria" to CountryBounds(18.9, 37.1, -8.7, 12.0),
        "Tunisia" to CountryBounds(30.2, 37.6, 7.5, 11.6),
        "Ukraine" to CountryBounds(44.3, 52.4, 22.1, 40.3),
        "Kazakhstan" to CountryBounds(40.5, 55.5, 46.5, 87.4),
        "Uzbekistan" to CountryBounds(37.1, 45.6, 56.0, 73.2),
        "Azerbaijan" to CountryBounds(38.3, 41.9, 44.7, 50.9),
        "Georgia" to CountryBounds(41.0, 43.6, 39.9, 46.8),
        "Armenia" to CountryBounds(38.8, 41.3, 43.4, 46.7),
        "Cyprus" to CountryBounds(34.5, 35.7, 32.2, 34.6),
        "Maldives" to CountryBounds(-0.7, 7.2, 72.5, 73.8),
        "Mauritius" to CountryBounds(-20.6, -19.9, 57.3, 57.8),
        "Bahamas" to CountryBounds(20.9, 27.3, -79.3, -72.7),
        "Jamaica" to CountryBounds(17.7, 18.6, -78.4, -76.1),
        "Cuba" to CountryBounds(19.8, 23.3, -85.0, -74.1),
        "Dominican Republic" to CountryBounds(17.4, 20.0, -72.1, -68.3),
        "Puerto Rico" to CountryBounds(17.8, 18.6, -67.3, -65.2),
        "Costa Rica" to CountryBounds(8.0, 11.3, -86.0, -82.5),
        "Panama" to CountryBounds(7.2, 9.7, -83.1, -77.1),
        "Ecuador" to CountryBounds(-5.1, 1.5, -92.1, -75.1),
        "Venezuela" to CountryBounds(0.6, 12.2, -73.4, -59.8),
        "Uruguay" to CountryBounds(-35.0, -30.0, -58.5, -53.0),
        "Paraguay" to CountryBounds(-27.6, -19.3, -62.7, -54.2),
        "Bolivia" to CountryBounds(-23.0, -9.6, -69.7, -57.5),
        "Ghana" to CountryBounds(4.7, 11.2, -3.3, 1.3),
        "Tanzania" to CountryBounds(-11.8, -0.9, 29.3, 40.5),
        "Uganda" to CountryBounds(-1.5, 4.3, 29.5, 35.1),
        "Ethiopia" to CountryBounds(3.4, 14.9, 32.9, 48.0),
        "Israel" to CountryBounds(29.4, 33.4, 34.2, 35.9),
        "Palestine" to CountryBounds(31.2, 32.6, 34.2, 35.6),
        "Yemen" to CountryBounds(12.0, 19.0, 41.8, 54.6),
        "Syria" to CountryBounds(32.3, 37.4, 35.6, 42.4),
        "Cambodia" to CountryBounds(10.4, 14.7, 102.3, 107.7),
        "Myanmar" to CountryBounds(9.5, 28.6, 92.1, 101.2),
        "Laos" to CountryBounds(13.9, 22.5, 100.0, 107.7)
    )

    fun isCountryValid(countryName: String?): Boolean {
        if (countryName.isNullOrBlank()) return false
        val trimmed = countryName.trim()
        val normalized = normalizeCountry(trimmed)
        return COUNTRY_SUGGESTIONS.any {
            isCountryMatch(it.country, trimmed) ||
            isCountryMatch(it.primaryTitle, trimmed) ||
            it.country.equals(normalized, ignoreCase = true) ||
            it.primaryTitle.equals(trimmed, ignoreCase = true)
        } || allIsoCountries.any {
            it.equals(trimmed, ignoreCase = true) ||
            it.equals(normalized, ignoreCase = true) ||
            isCountryMatch(it, trimmed)
        }
    }

    fun getCountryBounds(countryName: String?): CountryBounds? {
        if (countryName.isNullOrBlank()) return null
        val normalized = normalizeCountry(countryName)
        return COUNTRY_BOUNDS[normalized] ?: COUNTRY_BOUNDS.entries.firstOrNull {
            isCountryMatch(it.key, countryName)
        }?.value
    }

    fun isLocationWithinCountry(
        lat: Double,
        lon: Double,
        countryName: String?,
        context: Context? = null
    ): Boolean {
        if (countryName.isNullOrBlank()) return false
        if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return false

        val bounds = getCountryBounds(countryName)
        if (bounds != null) {
            return bounds.contains(lat, lon)
        }

        // For any country not in preset bounds, verify via Android Geocoder if available
        if (context != null) {
            try {
                val geocoder = Geocoder(context, Locale.getDefault())
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(lat, lon, 1)
                if (!addresses.isNullOrEmpty()) {
                    val resolvedCountry = addresses[0].countryName.orEmpty()
                    val resolvedCode = addresses[0].countryCode.orEmpty()
                    if (isCountryMatch(resolvedCountry, countryName) || isCountryMatch(resolvedCode, countryName)) {
                        return true
                    }
                }
            } catch (e: Exception) {
                // Geocoder offline fallback
            }
        }

        // Basic world coordinate boundary check fallback
        return lat in -90.0..90.0 && lon in -180.0..180.0
    }

    fun findSuggestionByText(text: String): LocationSuggestion? {
        val trimmed = text.trim()
        return FAMOUS_CITIES.firstOrNull {
            it.primaryTitle.equals(trimmed, ignoreCase = true) ||
            it.address.equals(trimmed, ignoreCase = true) ||
            it.country.equals(trimmed, ignoreCase = true)
        } ?: COUNTRY_SUGGESTIONS.firstOrNull {
            it.primaryTitle.equals(trimmed, ignoreCase = true) ||
            it.country.equals(trimmed, ignoreCase = true)
        }
    }
}
