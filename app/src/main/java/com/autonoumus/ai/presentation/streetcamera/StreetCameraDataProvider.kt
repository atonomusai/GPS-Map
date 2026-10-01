package com.autonoumus.ai.presentation.streetcamera

import com.autonoumus.ai.R
import com.autonoumus.ai.domain.model.StreetCamera

object StreetCameraDataProvider {

    fun getPlaceCategories(): List<PlaceCategoryModel> = listOf(
        PlaceCategoryModel("airport", "Airport", R.drawable.ic_cat_airport, "#E6FDF2", "#10B981"),
        PlaceCategoryModel("area", "Area", R.drawable.area_ic, "#FFE4E6", "#F43F5E"),
        PlaceCategoryModel("bay", "Bay", R.drawable.bay_ic, "#FEF3C7", "#D97706"),
        PlaceCategoryModel("beach", "Beach", R.drawable.beach_ic, "#E0F2FE", "#0284C7"),
        PlaceCategoryModel("building", "Building", R.drawable.buiding_ic, "#FFEDD5", "#EA580C"),
        PlaceCategoryModel("camping", "Camping", R.drawable.camping_ic, "#EDE9FE", "#6366F1"),
        PlaceCategoryModel("public_indoor", "Public Indoor", R.drawable.indoor_ic, "#FCE7F3", "#DB2777"),
        PlaceCategoryModel("sky", "Sky", R.drawable.sky_ic, "#E0F2FE", "#06B6D4"),
        PlaceCategoryModel("aquarium", "Aquarium", R.drawable.aquarium_ic, "#CCFBF1", "#0D9488"),
        PlaceCategoryModel("theater", "Theater", R.drawable.thater_ic, "#FFE4E6", "#E11D48"),
        PlaceCategoryModel("sports_area", "Sports Area", R.drawable.sports_ic, "#D1FAE5", "#10B981"),
        PlaceCategoryModel("square_alley", "Square/Alley", R.drawable.square_ic, "#FEF3C7", "#D97706"),
        PlaceCategoryModel("street_traffic", "Street/Traffic", R.drawable.trafic_ic, "#FEF3C7", "#D97706"),
        PlaceCategoryModel("water", "Water", R.drawable.water_ic, "#E0E7FF", "#4F46E5")
    )

    fun getCountries(): List<CountryCameraModel> = listOf(
        CountryCameraModel("US", "USA", "View webcams", R.drawable.flag_us),
        CountryCameraModel("GB", "United Kingdom", "View webcams", R.drawable.flag_gb),
        CountryCameraModel("DE", "Germany", "View webcams", R.drawable.flag_de),
        CountryCameraModel("FR", "France", "View webcams", R.drawable.flag_fr),
        CountryCameraModel("JP", "Japan", "View webcams", R.drawable.flag_jp),
        CountryCameraModel("CA", "Canada", "View webcams", R.drawable.flag_ca),
        CountryCameraModel("IT", "Italy", "View webcams", R.drawable.flag_it),
        CountryCameraModel("AU", "Australia", "View webcams", R.drawable.flag_au),
        CountryCameraModel("KR", "South Korea", "View webcams", R.drawable.flag_kr),
        CountryCameraModel("ES", "Spain", "View webcams", R.drawable.flag_es),
        CountryCameraModel("NL", "Netherlands", "View webcams", R.drawable.flag_nl),
        CountryCameraModel("CH", "Switzerland", "View webcams", R.drawable.flag_ch),
        CountryCameraModel("SE", "Sweden", "View webcams", R.drawable.flag_se),
        CountryCameraModel("NO", "Norway", "View webcams", R.drawable.flag_no),
        CountryCameraModel("BR", "Brazil", "View webcams", R.drawable.flag_br),
        CountryCameraModel("PL", "Poland", "View webcams", R.drawable.flag_pl)
    )

    fun getFeaturedLocations(): List<FeaturedLocationModel> = listOf(
        FeaturedLocationModel(
            id = "feat_1",
            title = "Times Square, NYC",
            subtitle = "New York, USA",
            cameraCountLabel = "View webcams",
            imageUrl = "https://images.unsplash.com/photo-1534430480872-3498386e7856?w=800&auto=format&fit=crop&q=80",
            isLive = false
        ),
        FeaturedLocationModel(
            id = "feat_2",
            title = "Shibuya Crossing",
            subtitle = "Tokyo, Japan",
            cameraCountLabel = "View webcams",
            imageUrl = "https://images.unsplash.com/photo-1542051841857-5f90071e7989?w=800&auto=format&fit=crop&q=80",
            isLive = false
        ),
        FeaturedLocationModel(
            id = "feat_3",
            title = "Eiffel Tower",
            subtitle = "Paris, France",
            cameraCountLabel = "View webcams",
            imageUrl = "https://images.unsplash.com/photo-1511739001486-6bfe10ce785f?w=800&auto=format&fit=crop&q=80",
            isLive = false
        ),
        FeaturedLocationModel(
            id = "feat_4",
            title = "Sydney Opera House",
            subtitle = "Sydney, Australia",
            cameraCountLabel = "View webcams",
            imageUrl = "https://images.unsplash.com/photo-1506973035872-a4ec16b8e8d9?w=800&auto=format&fit=crop&q=80",
            isLive = false
        ),
        FeaturedLocationModel(
            id = "feat_5",
            title = "Colosseum",
            subtitle = "Rome, Italy",
            cameraCountLabel = "View webcams",
            imageUrl = "https://images.unsplash.com/photo-1552832230-c0197dd311b5?w=800&auto=format&fit=crop&q=80",
            isLive = false
        )
    )

    fun getPopularCities(countryFilter: String? = null, categoryFilter: String? = null): List<PopularCityLocationModel> {
        if (!categoryFilter.isNullOrBlank()) {
            val catLocations = getLocationsForCategory(categoryFilter)
            if (catLocations.isNotEmpty()) return catLocations
        }

        val allCities = listOf(
            PopularCityLocationModel("c_1", "New York", "NY", "USA", 18, "https://images.unsplash.com/photo-1534430480872-3498386e7856?w=400&auto=format&fit=crop&q=80", "Cities"),
            PopularCityLocationModel("c_2", "Los Angeles", "CA", "USA", 12, "https://images.unsplash.com/photo-1580655653885-65763b2597d0?w=400&auto=format&fit=crop&q=80", "Cities"),
            PopularCityLocationModel("c_3", "Chicago", "IL", "USA", 9, "https://images.unsplash.com/photo-1494522855154-9297ac14b55f?w=400&auto=format&fit=crop&q=80", "Cities"),
            PopularCityLocationModel("c_4", "Miami", "FL", "USA", 7, "https://images.unsplash.com/photo-1506953823976-52e1fdc0149a?w=400&auto=format&fit=crop&q=80", "Beaches"),
            PopularCityLocationModel("c_5", "San Francisco", "CA", "USA", 6, "https://images.unsplash.com/photo-1501594907352-04cda38ebc29?w=400&auto=format&fit=crop&q=80", "Landmarks"),
            PopularCityLocationModel("c_6", "Las Vegas", "NV", "USA", 5, "https://images.unsplash.com/photo-1506059612708-99d6c258160e?w=400&auto=format&fit=crop&q=80", "Cities"),
            PopularCityLocationModel("c_7", "Washington", "DC", "USA", 5, "https://images.unsplash.com/photo-1541872703-74c5e44368f9?w=400&auto=format&fit=crop&q=80", "Landmarks"),
            PopularCityLocationModel("c_8", "Houston", "TX", "USA", 4, "https://images.unsplash.com/photo-1530089711124-9ca31fb9e863?w=400&auto=format&fit=crop&q=80", "Highways"),
            PopularCityLocationModel("c_9", "Boston", "MA", "USA", 4, "https://images.unsplash.com/photo-1501979376754-2ff867a4f659?w=400&auto=format&fit=crop&q=80", "Cities"),
            PopularCityLocationModel("c_10", "Seattle", "WA", "USA", 3, "https://images.unsplash.com/photo-1502175353174-a7a70e73b362?w=400&auto=format&fit=crop&q=80", "Cities"),
            PopularCityLocationModel("c_11", "Denver", "CO", "USA", 3, "https://images.unsplash.com/photo-1546554137-f86b9593a222?w=400&auto=format&fit=crop&q=80", "Highways"),
            PopularCityLocationModel("c_12", "Nashville", "TN", "USA", 2, "https://images.unsplash.com/photo-1543832923-44667a44c804?w=400&auto=format&fit=crop&q=80", "Cities"),
            PopularCityLocationModel("c_13", "Tokyo", "Kanto", "Japan", 24, "https://images.unsplash.com/photo-1503899036084-c55cdd92da26?w=400&auto=format&fit=crop&q=80", "Cities"),
            PopularCityLocationModel("c_14", "Kyoto", "Kansai", "Japan", 11, "https://images.unsplash.com/photo-1493976040374-85c8e12f0c0e?w=400&auto=format&fit=crop&q=80", "Landmarks"),
            PopularCityLocationModel("c_15", "Osaka", "Kansai", "Japan", 14, "https://images.unsplash.com/photo-1590559899731-a382839e5549?w=400&auto=format&fit=crop&q=80", "Cities"),
            PopularCityLocationModel("c_16", "London", "England", "United Kingdom", 19, "https://images.unsplash.com/photo-1513635269975-59663e0ac1ad?w=400&auto=format&fit=crop&q=80", "Cities"),
            PopularCityLocationModel("c_17", "Edinburgh", "Scotland", "United Kingdom", 8, "https://images.unsplash.com/photo-1506377585622-bedcbb027afc?w=400&auto=format&fit=crop&q=80", "Landmarks"),
            PopularCityLocationModel("c_18", "Berlin", "Brandenburg", "Germany", 14, "https://images.unsplash.com/photo-1560969184-10fe8719e047?w=400&auto=format&fit=crop&q=80", "Cities"),
            PopularCityLocationModel("c_19", "Munich", "Bavaria", "Germany", 12, "https://images.unsplash.com/photo-1595867818082-083862f3d630?w=400&auto=format&fit=crop&q=80", "Cities"),
            PopularCityLocationModel("c_20", "Paris", "Île-de-France", "France", 15, "https://images.unsplash.com/photo-1502602898657-3e91760cbb34?w=400&auto=format&fit=crop&q=80", "Cities"),
            PopularCityLocationModel("c_21", "Nice", "Provence", "France", 9, "https://images.unsplash.com/photo-1533105079780-92b9be482077?w=400&auto=format&fit=crop&q=80", "Beaches"),
            PopularCityLocationModel("c_22", "Rome", "Lazio", "Italy", 11, "https://images.unsplash.com/photo-1552832230-c0197dd311b5?w=400&auto=format&fit=crop&q=80", "Landmarks"),
            PopularCityLocationModel("c_23", "Venice", "Veneto", "Italy", 8, "https://images.unsplash.com/photo-1514890547357-a9ee288728e0?w=400&auto=format&fit=crop&q=80", "Landmarks"),
            PopularCityLocationModel("c_24", "Sydney", "NSW", "Australia", 9, "https://images.unsplash.com/photo-1507525428034-b723cf961d3e?w=400&auto=format&fit=crop&q=80", "Beaches"),
            PopularCityLocationModel("c_25", "Melbourne", "VIC", "Australia", 7, "https://images.unsplash.com/photo-1514395462725-fb4566210144?w=400&auto=format&fit=crop&q=80", "Cities"),
            PopularCityLocationModel("c_26", "Toronto", "ON", "Canada", 10, "https://images.unsplash.com/photo-1517090504586-fde19ea6066f?w=400&auto=format&fit=crop&q=80", "Cities"),
            PopularCityLocationModel("c_27", "Vancouver", "BC", "Canada", 8, "https://images.unsplash.com/photo-1559511260-66a65e0982d5?w=400&auto=format&fit=crop&q=80", "Beaches"),
            PopularCityLocationModel("c_28", "Madrid", "Community of Madrid", "Spain", 10, "https://images.unsplash.com/photo-1539037116277-4db20889f2d4?w=400&auto=format&fit=crop&q=80", "Cities"),
            PopularCityLocationModel("c_29", "Barcelona", "Catalonia", "Spain", 13, "https://images.unsplash.com/photo-1583422409516-2895a77efded?w=400&auto=format&fit=crop&q=80", "Beaches"),
            PopularCityLocationModel("c_30", "Seoul", "Capital Region", "South Korea", 16, "https://images.unsplash.com/photo-1538485399081-7191377e8241?w=400&auto=format&fit=crop&q=80", "Cities"),
            PopularCityLocationModel("c_31", "Busan", "Yeongnam", "South Korea", 9, "https://images.unsplash.com/photo-1578637387939-43c525550085?w=400&auto=format&fit=crop&q=80", "Beaches"),
            PopularCityLocationModel("c_32", "Amsterdam", "North Holland", "Netherlands", 12, "https://images.unsplash.com/photo-1512470876302-972faa2aa9a4?w=400&auto=format&fit=crop&q=80", "Cities"),
            PopularCityLocationModel("c_33", "Zurich", "Canton of Zurich", "Switzerland", 7, "https://images.unsplash.com/photo-1515488764276-beab7607c1e6?w=400&auto=format&fit=crop&q=80", "Landmarks"),
            PopularCityLocationModel("c_34", "Stockholm", "Stockholm County", "Sweden", 8, "https://images.unsplash.com/photo-1509356843151-3e7d96241e11?w=400&auto=format&fit=crop&q=80", "Cities"),
            PopularCityLocationModel("c_35", "Oslo", "Eastern Norway", "Norway", 6, "https://images.unsplash.com/photo-1513519245088-0e12902e5a38?w=400&auto=format&fit=crop&q=80", "Highways"),
            PopularCityLocationModel("c_36", "Rio de Janeiro", "Southeast", "Brazil", 11, "https://images.unsplash.com/photo-1483729558449-99ef09a8c325?w=400&auto=format&fit=crop&q=80", "Beaches"),
            PopularCityLocationModel("c_37", "São Paulo", "Southeast", "Brazil", 14, "https://images.unsplash.com/photo-1578059427060-4337bf22262a?w=400&auto=format&fit=crop&q=80", "Highways"),
            PopularCityLocationModel("c_38", "Singapore", "Marina Bay", "Singapore", 15, "https://images.unsplash.com/photo-1525625293386-3f8f99389edd?w=400&auto=format&fit=crop&q=80", "Landmarks"),
            PopularCityLocationModel("c_39", "Warsaw", "Masovian", "Poland", 12, "https://images.unsplash.com/photo-1519197924294-4ba991a11128?w=400&auto=format&fit=crop&q=80", "Cities"),
            PopularCityLocationModel("c_40", "Kraków", "Lesser Poland", "Poland", 9, "https://images.unsplash.com/photo-1574691250077-03a929faece5?w=400&auto=format&fit=crop&q=80", "Landmarks")
        )

        var result = allCities
        if (!countryFilter.isNullOrBlank()) {
            result = result.filter { it.country.equals(countryFilter, ignoreCase = true) }
            if (result.isEmpty()) result = allCities
        }
        return result
    }

    fun getLocationsForCategory(category: String): List<PopularCityLocationModel> {
        val cat = category.trim().lowercase()
        return when {
            cat.contains("beach") -> listOf(
                PopularCityLocationModel("b_1", "Miami South Beach", "FL", "USA", 14, "https://images.unsplash.com/photo-1506953823976-52e1fdc0149a?w=400&auto=format&fit=crop&q=80", "Beaches"),
                PopularCityLocationModel("b_2", "Bondi Beach", "Sydney", "Australia", 10, "https://images.unsplash.com/photo-1507525428034-b723cf961d3e?w=400&auto=format&fit=crop&q=80", "Beaches"),
                PopularCityLocationModel("b_3", "Waikiki Beach", "Honolulu, HI", "USA", 8, "https://images.unsplash.com/photo-1542259009477-d625272157b7?w=400&auto=format&fit=crop&q=80", "Beaches"),
                PopularCityLocationModel("b_4", "Copacabana Beach", "Rio de Janeiro", "Brazil", 12, "https://images.unsplash.com/photo-1483729558449-99ef09a8c325?w=400&auto=format&fit=crop&q=80", "Beaches"),
                PopularCityLocationModel("b_5", "Promenade des Anglais", "Nice", "France", 9, "https://images.unsplash.com/photo-1533105079780-92b9be482077?w=400&auto=format&fit=crop&q=80", "Beaches"),
                PopularCityLocationModel("b_6", "Barceloneta Beach", "Barcelona", "Spain", 11, "https://images.unsplash.com/photo-1583422409516-2895a77efded?w=400&auto=format&fit=crop&q=80", "Beaches"),
                PopularCityLocationModel("b_7", "Haeundae Beach", "Busan", "South Korea", 8, "https://images.unsplash.com/photo-1578637387939-43c525550085?w=400&auto=format&fit=crop&q=80", "Beaches"),
                PopularCityLocationModel("b_8", "Venice Beach", "Los Angeles, CA", "USA", 7, "https://images.unsplash.com/photo-1507525428034-b723cf961d3e?w=400&auto=format&fit=crop&q=80", "Beaches")
            )

            cat.contains("airport") -> listOf(
                PopularCityLocationModel("air_1", "JFK International", "New York, NY", "USA", 8, "https://images.unsplash.com/photo-1530521954074-e64f6810b32d?w=400&auto=format&fit=crop&q=80", "Airports"),
                PopularCityLocationModel("air_2", "Heathrow Airport", "London", "United Kingdom", 11, "https://images.unsplash.com/photo-1542296332-2e4473faf563?w=400&auto=format&fit=crop&q=80", "Airports"),
                PopularCityLocationModel("air_3", "Haneda International", "Tokyo", "Japan", 9, "https://images.unsplash.com/photo-1570710891163-6d3b5c47248b?w=400&auto=format&fit=crop&q=80", "Airports"),
                PopularCityLocationModel("air_4", "Frankfurt Airport", "Hesse", "Germany", 7, "https://images.unsplash.com/photo-1529074963764-98f45c47344b?w=400&auto=format&fit=crop&q=80", "Airports"),
                PopularCityLocationModel("air_5", "LAX Airport", "Los Angeles, CA", "USA", 6, "https://images.unsplash.com/photo-1436491865332-7a61a109cc05?w=400&auto=format&fit=crop&q=80", "Airports"),
                PopularCityLocationModel("air_6", "Charles de Gaulle", "Paris", "France", 8, "https://images.unsplash.com/photo-1508873696983-2df5293cb32f?w=400&auto=format&fit=crop&q=80", "Airports")
            )

            cat.contains("bay") -> listOf(
                PopularCityLocationModel("bay_1", "San Francisco Bay", "CA", "USA", 12, "https://images.unsplash.com/photo-1501594907352-04cda38ebc29?w=400&auto=format&fit=crop&q=80", "Bays"),
                PopularCityLocationModel("bay_2", "Tokyo Bay", "Kanto", "Japan", 15, "https://images.unsplash.com/photo-1503899036084-c55cdd92da26?w=400&auto=format&fit=crop&q=80", "Bays"),
                PopularCityLocationModel("bay_3", "Sydney Harbour Bay", "NSW", "Australia", 14, "https://images.unsplash.com/photo-1506973035872-a4ec16b8e8d9?w=400&auto=format&fit=crop&q=80", "Bays"),
                PopularCityLocationModel("bay_4", "English Bay", "Vancouver, BC", "Canada", 8, "https://images.unsplash.com/photo-1559511260-66a65e0982d5?w=400&auto=format&fit=crop&q=80", "Bays"),
                PopularCityLocationModel("bay_5", "Naples Bay Harbor", "Campania", "Italy", 7, "https://images.unsplash.com/photo-1533105079780-92b9be482077?w=400&auto=format&fit=crop&q=80", "Bays")
            )

            cat.contains("building") -> listOf(
                PopularCityLocationModel("bld_1", "Empire State Building", "New York, NY", "USA", 16, "https://images.unsplash.com/photo-1534430480872-3498386e7856?w=400&auto=format&fit=crop&q=80", "Buildings"),
                PopularCityLocationModel("bld_2", "Marina Bay Sands", "Downtown", "Singapore", 12, "https://images.unsplash.com/photo-1525625293386-3f8f99389edd?w=400&auto=format&fit=crop&q=80", "Buildings"),
                PopularCityLocationModel("bld_3", "CN Tower", "Toronto, ON", "Canada", 9, "https://images.unsplash.com/photo-1517090504586-fde19ea6066f?w=400&auto=format&fit=crop&q=80", "Buildings"),
                PopularCityLocationModel("bld_4", "The Shard", "London", "United Kingdom", 11, "https://images.unsplash.com/photo-1513635269975-59663e0ac1ad?w=400&auto=format&fit=crop&q=80", "Buildings"),
                PopularCityLocationModel("bld_5", "Tokyo Skytree", "Tokyo", "Japan", 14, "https://images.unsplash.com/photo-1503899036084-c55cdd92da26?w=400&auto=format&fit=crop&q=80", "Buildings")
            )

            cat.contains("camping") -> listOf(
                PopularCityLocationModel("camp_1", "Yellowstone National Park", "WY", "USA", 9, "https://images.unsplash.com/photo-1464822759023-fed622ff2c3b?w=400&auto=format&fit=crop&q=80", "Camping"),
                PopularCityLocationModel("camp_2", "Yosemite Valley", "CA", "USA", 11, "https://images.unsplash.com/photo-1426604966848-d7adac402bff?w=400&auto=format&fit=crop&q=80", "Camping"),
                PopularCityLocationModel("camp_3", "Banff National Park", "AB", "Canada", 14, "https://images.unsplash.com/photo-1506744038136-46273834b3fb?w=400&auto=format&fit=crop&q=80", "Camping"),
                PopularCityLocationModel("camp_4", "Grand Canyon Camp", "AZ", "USA", 8, "https://images.unsplash.com/photo-1474044159687-1ee9f3a51722?w=400&auto=format&fit=crop&q=80", "Camping"),
                PopularCityLocationModel("camp_5", "Swiss Alps Zermatt", "Valais", "Switzerland", 10, "https://images.unsplash.com/photo-1530122037265-a5f1f91d3b99?w=400&auto=format&fit=crop&q=80", "Camping")
            )

            cat.contains("water") -> listOf(
                PopularCityLocationModel("wat_1", "Niagara Falls", "NY", "USA", 15, "https://images.unsplash.com/photo-1533094602577-199e3d9f8a7a?w=400&auto=format&fit=crop&q=80", "Water"),
                PopularCityLocationModel("wat_2", "Grand Canal", "Venice", "Italy", 12, "https://images.unsplash.com/photo-1514890547357-a9ee288728e0?w=400&auto=format&fit=crop&q=80", "Water"),
                PopularCityLocationModel("wat_3", "Lake Geneva", "Geneva", "Switzerland", 9, "https://images.unsplash.com/photo-1515488764276-beab7607c1e6?w=400&auto=format&fit=crop&q=80", "Water"),
                PopularCityLocationModel("wat_4", "Lake Como", "Lombardy", "Italy", 8, "https://images.unsplash.com/photo-1544644181-1484b3fdfc62?w=400&auto=format&fit=crop&q=80", "Water"),
                PopularCityLocationModel("wat_5", "Lake Louise", "AB", "Canada", 10, "https://images.unsplash.com/photo-1503614472-8c93d56e92ce?w=400&auto=format&fit=crop&q=80", "Water")
            )

            cat.contains("sports") -> listOf(
                PopularCityLocationModel("spt_1", "Madison Square Garden", "New York, NY", "USA", 6, "https://images.unsplash.com/photo-1516450360452-9312f5e86fc7?w=400&auto=format&fit=crop&q=80", "Sports"),
                PopularCityLocationModel("spt_2", "Camp Nou Stadium", "Barcelona", "Spain", 8, "https://images.unsplash.com/photo-1522778119026-d647f0596c20?w=400&auto=format&fit=crop&q=80", "Sports"),
                PopularCityLocationModel("spt_3", "Wembley Stadium", "London", "United Kingdom", 7, "https://images.unsplash.com/photo-1508098682722-e99c43a406b2?w=400&auto=format&fit=crop&q=80", "Sports"),
                PopularCityLocationModel("spt_4", "Tokyo Dome Arena", "Tokyo", "Japan", 9, "https://images.unsplash.com/photo-1503899036084-c55cdd92da26?w=400&auto=format&fit=crop&q=80", "Sports")
            )

            cat.contains("aquarium") -> listOf(
                PopularCityLocationModel("aq_1", "Monterey Bay Aquarium", "CA", "USA", 8, "https://images.unsplash.com/photo-1524704654690-b56c05c78a00?w=400&auto=format&fit=crop&q=80", "Aquariums"),
                PopularCityLocationModel("aq_2", "Georgia Aquarium", "Atlanta, GA", "USA", 10, "https://images.unsplash.com/photo-1544551763-46a013bb70d5?w=400&auto=format&fit=crop&q=80", "Aquariums"),
                PopularCityLocationModel("aq_3", "Okinawa Churaumi", "Okinawa", "Japan", 7, "https://images.unsplash.com/photo-1535591273668-578e31182c4f?w=400&auto=format&fit=crop&q=80", "Aquariums")
            )

            cat.contains("theater") -> listOf(
                PopularCityLocationModel("th_1", "Broadway Theatre District", "New York, NY", "USA", 11, "https://images.unsplash.com/photo-1514306191717-452ec28c7814?w=400&auto=format&fit=crop&q=80", "Theaters"),
                PopularCityLocationModel("th_2", "Sydney Opera Hall", "Sydney", "Australia", 6, "https://images.unsplash.com/photo-1506973035872-a4ec16b8e8d9?w=400&auto=format&fit=crop&q=80", "Theaters"),
                PopularCityLocationModel("th_3", "Palais Garnier Opera", "Paris", "France", 8, "https://images.unsplash.com/photo-1511739001486-6bfe10ce785f?w=400&auto=format&fit=crop&q=80", "Theaters"),
                PopularCityLocationModel("th_4", "West End Theatres", "London", "United Kingdom", 9, "https://images.unsplash.com/photo-1513635269975-59663e0ac1ad?w=400&auto=format&fit=crop&q=80", "Theaters")
            )

            cat.contains("indoor") -> listOf(
                PopularCityLocationModel("ind_1", "Grand Central Terminal", "New York, NY", "USA", 9, "https://images.unsplash.com/photo-1534430480872-3498386e7856?w=400&auto=format&fit=crop&q=80", "Indoor"),
                PopularCityLocationModel("ind_2", "Galleria Vittorio Emanuele", "Milan", "Italy", 7, "https://images.unsplash.com/photo-1552832230-c0197dd311b5?w=400&auto=format&fit=crop&q=80", "Indoor"),
                PopularCityLocationModel("ind_3", "St Pancras Grand Station", "London", "United Kingdom", 8, "https://images.unsplash.com/photo-1513635269975-59663e0ac1ad?w=400&auto=format&fit=crop&q=80", "Indoor"),
                PopularCityLocationModel("ind_4", "Kyoto Station Hall", "Kyoto", "Japan", 6, "https://images.unsplash.com/photo-1493976040374-85c8e12f0c0e?w=400&auto=format&fit=crop&q=80", "Indoor")
            )

            cat.contains("sky") -> listOf(
                PopularCityLocationModel("sky_1", "Mauna Kea Observatory", "HI", "USA", 5, "https://images.unsplash.com/photo-1506703719100-a0f3a48c0f86?w=400&auto=format&fit=crop&q=80", "Sky"),
                PopularCityLocationModel("sky_2", "Tromsø Aurora Sky", "Troms", "Norway", 8, "https://images.unsplash.com/photo-1517411032315-54ef2cb783bb?w=400&auto=format&fit=crop&q=80", "Sky"),
                PopularCityLocationModel("sky_3", "Jungfraujoch Observatory", "Bern", "Switzerland", 6, "https://images.unsplash.com/photo-1530122037265-a5f1f91d3b99?w=400&auto=format&fit=crop&q=80", "Sky")
            )

            cat.contains("square") || cat.contains("alley") -> listOf(
                PopularCityLocationModel("sq_1", "Times Square Plaza", "New York, NY", "USA", 18, "https://images.unsplash.com/photo-1534430480872-3498386e7856?w=400&auto=format&fit=crop&q=80", "Squares"),
                PopularCityLocationModel("sq_2", "Gion Historic Alleys", "Kyoto", "Japan", 11, "https://images.unsplash.com/photo-1493976040374-85c8e12f0c0e?w=400&auto=format&fit=crop&q=80", "Squares"),
                PopularCityLocationModel("sq_3", "Montmartre Alleys", "Paris", "France", 9, "https://images.unsplash.com/photo-1502602898657-3e91760cbb34?w=400&auto=format&fit=crop&q=80", "Squares"),
                PopularCityLocationModel("sq_4", "Trastevere Alleys", "Rome", "Italy", 7, "https://images.unsplash.com/photo-1552832230-c0197dd311b5?w=400&auto=format&fit=crop&q=80", "Squares")
            )

            cat.contains("traffic") || cat.contains("street") -> listOf(
                PopularCityLocationModel("tr_1", "Shibuya Scramble Crossing", "Tokyo", "Japan", 24, "https://images.unsplash.com/photo-1542051841857-5f90071e7989?w=400&auto=format&fit=crop&q=80", "Traffic"),
                PopularCityLocationModel("tr_2", "7th Avenue & 42nd St", "New York, NY", "USA", 15, "https://images.unsplash.com/photo-1534430480872-3498386e7856?w=400&auto=format&fit=crop&q=80", "Traffic"),
                PopularCityLocationModel("tr_3", "Avenue des Champs-Élysées", "Paris", "France", 12, "https://images.unsplash.com/photo-1502602898657-3e91760cbb34?w=400&auto=format&fit=crop&q=80", "Traffic"),
                PopularCityLocationModel("tr_4", "Santa Monica Boulevard", "Los Angeles, CA", "USA", 10, "https://images.unsplash.com/photo-1580655653885-65763b2597d0?w=400&auto=format&fit=crop&q=80", "Traffic")
            )

            cat.contains("area") -> listOf(
                PopularCityLocationModel("ar_1", "Trafalgar Square Area", "London", "United Kingdom", 14, "https://images.unsplash.com/photo-1513635269975-59663e0ac1ad?w=400&auto=format&fit=crop&q=80", "Area"),
                PopularCityLocationModel("ar_2", "Grand Place", "Brussels", "Belgium", 9, "https://images.unsplash.com/photo-1512470876302-972faa2aa9a4?w=400&auto=format&fit=crop&q=80", "Area"),
                PopularCityLocationModel("ar_3", "Duomo Plaza Area", "Milan", "Italy", 10, "https://images.unsplash.com/photo-1552832230-c0197dd311b5?w=400&auto=format&fit=crop&q=80", "Area"),
                PopularCityLocationModel("ar_4", "Brandenburg Gate Plaza", "Berlin", "Germany", 12, "https://images.unsplash.com/photo-1560969184-10fe8719e047?w=400&auto=format&fit=crop&q=80", "Area")
            )

            else -> emptyList()
        }
    }

    /**
     * Shown when Windy returns nothing / can't be reached. Intentionally empty: the old fallback
     * showed stock photos labelled as live cameras linking to YouTube videos that no longer exist.
     */
    fun getFallbackLiveStreetCameras(): List<StreetCamera> = emptyList()
}
