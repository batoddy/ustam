package com.ustam.backend

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Seeds a handful of realistic demo accounts/jobs on first boot (empty DB only) —
 * mirrors the scenarios proven useful in the Django project's seed_dummy_jobs command:
 * an empty-state job, multi-proposal comparison, a provider juggling two non-conflicting
 * active jobs, a completed+rated job, a cancelled job, and a date that deliberately
 * overlaps one of the provider's busy dates (to exercise per-date conflict filtering). */
fun seedIfEmpty() {
    transaction {
        if (Users.selectAll().count() > 0) return@transaction

        fun user(username: String, email: String, first: String, last: String, role: String, phone: String = "5551112233") =
            Users.insertAndGetId {
                it[Users.username] = username
                it[Users.email] = email
                it[passwordHash] = PasswordHasher.hash("test1234")
                it[firstName] = first
                it[lastName] = last
                it[Users.role] = role
                it[Users.phone] = phone
                it[createdAt] = LocalDateTime.now()
            }.value

        val customer1 = user("testmusteri", "musteri@ustam.test", "Sena", "Çelik", "customer")
        val customer2 = user("aysemusteri2", "ayse@ustam.test", "Ayşe", "Kara", "customer")
        val provider1 = user("testusta", "usta@ustam.test", "Ahmet", "Yılmaz", "provider")
        val provider2 = user("aliusta", "ali@ustam.test", "Ali", "Demir", "provider")

        Users.update({ Users.id eq provider1 }) {
            it[serviceCategories] = encodeStringList(listOf("elektrik", "tesisat"))
        }

        fun job(customer: Int, title: String, category: String, subcategory: String, desc: String, dates: List<String>, location: String, status: String, provider: Int? = null) =
            Jobs.insertAndGetId {
                it[customerId] = customer
                it[Jobs.providerId] = provider
                it[Jobs.title] = title
                it[description] = desc
                it[Jobs.category] = category
                it[Jobs.subcategory] = subcategory
                it[Jobs.location] = location
                it[availableDates] = encodeStringList(dates)
                it[Jobs.status] = status
                it[createdAt] = LocalDateTime.now()
                it[updatedAt] = LocalDateTime.now()
            }.value

        fun proposal(jobId: Int, provider: Int, price: String, date: String?, time: String?, duration: String?, status: String, msg: String = "") =
            Proposals.insertAndGetId {
                it[Proposals.jobId] = jobId
                it[providerId] = provider
                it[message] = msg
                it[Proposals.price] = BigDecimal(price)
                it[proposedDate] = date?.let { d -> LocalDate.parse(d) }
                it[proposedTime] = time?.let { t -> LocalTime.parse(t) }
                it[durationHours] = duration?.let { h -> BigDecimal(h) }
                it[Proposals.status] = status
                it[createdAt] = LocalDateTime.now()
            }.value

        // 1. Boş ilan (henüz teklif yok)
        job(customer1, "Salonda priz arızası var", "elektrik", "Priz & Anahtar",
            "Salon prizlerinden biri kıvılcım yapıyor.", listOf("2026-08-01", "2026-08-02"), "Kadıköy, İstanbul", JobStatus.OPEN)

        // 2. Çoklu teklif (karşılaştırma senaryosu)
        val jobWithProposals = job(customer1, "Mutfak dolabı montajı", "marangoz", "Mutfak Dolabı",
            "IKEA mutfak dolabı montajı gerekiyor.", listOf("2026-08-05", "2026-08-06"), "Üsküdar, İstanbul", JobStatus.OPEN)
        proposal(jobWithProposals, provider1, "1200", "2026-08-05", "10:00", "3", ProposalStatus.PENDING, "Yarın uygunum.")
        proposal(jobWithProposals, provider2, "950", "2026-08-06", "14:00", "2.5", ProposalStatus.PENDING, "Deneyimliyim, hızlı hallederim.")

        // 3. Tek teklif
        val jobOneProposal = job(customer2, "Salon boyanacak", "boya", "İç Mekan Boya",
            "25 metrekare salon boyası.", listOf("2026-08-10"), "Ataşehir, İstanbul", JobStatus.OPEN)
        proposal(jobOneProposal, provider2, "2100", "2026-08-10", "09:00", "6", ProposalStatus.PENDING)

        // 4. Boş ilan (başka bir empty-state örneği)
        job(customer2, "Ev taşıma - 2+1 daire", "nakliye", "Ev Taşıma",
            "2+1 daireden 3+1 daireye taşınma.", listOf("2026-08-15"), "Maltepe, İstanbul", JobStatus.OPEN)

        // 5 & 6. Aynı ustanın çakışmayan iki aktif işi
        val active1 = job(customer1, "Kombi bakımı", "tesisat", "Kombi & Isıtma",
            "Yıllık kombi bakımı.", listOf("2026-07-21"), "Kadıköy, İstanbul", JobStatus.ACTIVE, provider1)
        proposal(active1, provider1, "450", "2026-07-21", "10:00", "1", ProposalStatus.ACCEPTED)

        val active2 = job(customer2, "Elektrik panosu yenileme", "elektrik", "Sigorta & Pano",
            "Eski sigorta panosunun yenilenmesi.", listOf("2026-07-24"), "Beşiktaş, İstanbul", JobStatus.ACTIVE, provider1)
        proposal(active2, provider1, "1800", "2026-07-24", "13:00", "4", ProposalStatus.ACCEPTED)

        // 7 & 8. Tamamlanmış + puanlanmış işler
        val completed1 = job(customer1, "Banyo tesisatı tamiri", "tesisat", "Banyo Tesisatı",
            "Gider tıkanıklığı giderildi.", listOf("2026-07-10"), "Kadıköy, İstanbul", JobStatus.COMPLETED, provider1)
        proposal(completed1, provider1, "600", "2026-07-10", "11:00", "2", ProposalStatus.ACCEPTED)
        Ratings.insertAndGetId {
            it[jobId] = completed1
            it[customerId] = customer1
            it[providerId] = provider1
            it[score] = 5
            it[comment] = "Çok iyi iş çıkardı, teşekkürler!"
            it[createdAt] = LocalDateTime.now()
        }

        val completed2 = job(customer2, "Ofis genel temizliği", "temizlik", "Genel Temizlik",
            "Haftalık ofis temizliği.", listOf("2026-07-08"), "Şişli, İstanbul", JobStatus.COMPLETED, provider2)
        proposal(completed2, provider2, "800", "2026-07-08", "09:00", "5", ProposalStatus.ACCEPTED)
        Ratings.insertAndGetId {
            it[jobId] = completed2
            it[customerId] = customer2
            it[providerId] = provider2
            it[score] = 4
            it[comment] = "İyi iş, biraz geç kaldı."
            it[createdAt] = LocalDateTime.now()
        }

        // 9. İptal edilmiş iş (usta hiç atanmadı)
        job(customer1, "Balkon cephe boyası", "boya", "Dış Cephe",
            "Balkon cephesi boyanacaktı, vazgeçildi.", listOf("2026-07-15"), "Kadıköy, İstanbul", JobStatus.CANCELLED)

        // 10. testusta'nın 21 Temmuz'da dolu olduğu tarihle KISMEN çakışan bir ilan:
        // usta 21'i için teklif veremez ama 22'si (çakışmayan gün) için verebilmeli.
        job(customer2, "Priz ve anahtar değişimi", "elektrik", "Priz & Anahtar",
            "3 adet priz, 2 adet anahtar değişimi.", listOf("2026-07-21", "2026-07-22"), "Kadıköy, İstanbul", JobStatus.OPEN)
    }
}
