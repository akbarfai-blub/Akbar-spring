package com.transaksi.Akbar.service.implement;

import com.transaksi.Akbar.DTO.KendaraanResponse;
import com.transaksi.Akbar.DTO.PegawaiResponse;
import com.transaksi.Akbar.DTO.PenyewaResponse;
import com.transaksi.Akbar.DTO.TransaksiDTO;
import com.transaksi.Akbar.DTO.PengembalianDTO;
import com.transaksi.Akbar.entity.TransaksiEntity;
import com.transaksi.Akbar.entity.PengembalianEntity;
import com.transaksi.Akbar.repository.TransaksiRepository;
import com.transaksi.Akbar.repository.PengembalianRepository;
import com.transaksi.Akbar.service.TransaksiService;
// import com.transaksi.Akbar.config.ApplicationConfig;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;


@Service
public class TransaksiServiceImpl implements TransaksiService {
    private final TransaksiRepository transaksiRepository;
    private final PengembalianRepository pengembalianRepository;
    private final RestTemplate restTemplate;

    // informasi API yang digunakan (ubahlah sesuai kebutuhan) <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<
//     private static String yuridan = "nitro5-an515-52-73y8:9001";
//     private static String charlie = "nitro5-an515-52-73y8:7777";
    // private static String Pegawai = "localhost:6969";

//    private static String charlie = "http://charlie.serveo.net";
//    private static String yuridan = "http://yuridan.serveo.net";

    private static String charlie = "https://charlie-production-655b.up.railway.app";
    private static String yuridan = "https://yuridan-production.up.railway.app";
    private static final Logger LOGGER = LoggerFactory.getLogger(TransaksiServiceImpl.class);

    // informasi API yang digunakan (ubahlah sesuai kebutuhan) <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<
    @Autowired
    public TransaksiServiceImpl(TransaksiRepository transaksiRepository, PengembalianRepository pengembalianRepository, RestTemplate restTemplate) {
        this.transaksiRepository = transaksiRepository;
        this.pengembalianRepository = pengembalianRepository;
        this.restTemplate = restTemplate;
    }
    final Logger logger = LoggerFactory.getLogger(TransaksiServiceImpl.class);

    @Override
    public void createTransaksi(TransaksiDTO transaksiDTO) throws RuntimeException {
        try {
            Integer idKendaraan = transaksiDTO.getIdKendaraan();
            // Integer versiKendaraan = transaksiDTO.getVersiKendaraan();s
            Integer idPenyewa = transaksiDTO.getIdPenyewa();

            // Cek status kendaraan
            String kendaraanUrl =  yuridan + "/kendaraan/find/id:" + idKendaraan;
            KendaraanResponse kendaraanResponse = restTemplate.getForObject(kendaraanUrl, KendaraanResponse.class);
            if (kendaraanResponse == null || !kendaraanResponse.isStatusKetersediaan()) {
                throw new RuntimeException("Maaf, kendaraan dengan ID " + idKendaraan + " sedang disewa.");
            }

            // Cek status sedang sewa
            String penyewaUrl =  charlie + "/penyewa/find/" + idPenyewa;
            PenyewaResponse penyewaResponse = restTemplate.getForObject(penyewaUrl, PenyewaResponse.class);
            if (penyewaResponse == null || penyewaResponse.isStatusSedangSewa()) {
                throw new RuntimeException("Penyewa sedang memiliki transaksi aktif.");
            }

            // Buat URL dengan parameter idKendaraan
            String url =  yuridan + "/kendaraan/find/id:" + idKendaraan;

            // Set header Content-Type: application/json
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            UriComponentsBuilder builder;
            ResponseEntity<KendaraanResponse> responseEntity;

            try {
                // Buat URI dengan URL dan parameter
                builder = UriComponentsBuilder.fromUriString(url);

                // Buat request dengan method GET dan header
                responseEntity = restTemplate.exchange(
                        builder.build().encode().toUri(),
                        HttpMethod.GET,
                        new HttpEntity<>(headers),
                        KendaraanResponse.class
                );
            } catch (Exception e) {
                logger.info(e.getMessage());
                throw new RuntimeException("Gagal mendapatkan data kendaraan.");
            }

            // Dapatkan response body
            KendaraanResponse response = responseEntity.getBody();

            // Misalkan response mengandung versiKendaraan dan hargaSewa
            Integer versiKendaraan = response.getVersion();
            Integer hargaSewa = response.getHargaSewa();

            // Hitung totalHargaSewa
            Date tanggalSewa = transaksiDTO.getTanggalSewa();
            Date tanggalKembali = transaksiDTO.getTanggalKembali();
            long millisecondsPerDay = 24 * 60 * 60 * 1000; // Jumlah milidetik dalam sehari
            long selisihHari = (tanggalKembali.getTime() - tanggalSewa.getTime()) / millisecondsPerDay;
            int totalHarga = hargaSewa * ((int) selisihHari + 1);

            // Buat objek TransaksiEntity
            TransaksiEntity transaksiEntity = new TransaksiEntity();
            transaksiEntity.setIdKendaraan(idKendaraan);
            transaksiEntity.setVersiKendaraan(versiKendaraan);
            transaksiEntity.setHargaSewa(hargaSewa);
            transaksiEntity.setTotalHargaSewa(totalHarga);
            transaksiEntity.setTanggalSewa(tanggalSewa);
            transaksiEntity.setTanggalKembali(tanggalKembali);
            transaksiEntity.setIdPenyewa(transaksiDTO.getIdPenyewa());
            transaksiEntity.setIdPegawai(transaksiDTO.getIdPegawai());

            // Simpan transaksi
            transaksiRepository.save(transaksiEntity);

            // Ubah status kendaraan menjadi tidak tersedia
            String ubahStatusKendaraanUrl =  yuridan + "/kendaraan/status/" + idKendaraan + "/false";
            restTemplate.put(ubahStatusKendaraanUrl, null);

            // Ubah status sedang sewa menjadi true
            String ubahStatusSedangSewaUrl =  charlie + "/penyewa/sedang-sewa/" + idPenyewa + "/true";
            restTemplate.put(ubahStatusSedangSewaUrl, null);

            // Buat objek PengembalianEntity
            PengembalianEntity pengembalianEntity = new PengembalianEntity();
            pengembalianEntity.setTransaksi(transaksiEntity);
            pengembalianEntity.setStatusSewa("sedang_proses"); // Status awal transaksi
            pengembalianEntity.setNotePengembalian("tidak ada catatan"); // Default note pengembalian
            pengembalianEntity.setTambahanHari(0); // Default tambahan hari
            pengembalianEntity.setTotalBayar(0); // Default total bayar (bisa diubah sesuai kebutuhan)

            // Simpan pengembalian
            pengembalianRepository.save(pengembalianEntity);

            logger.info("finish");
        } catch (HttpClientErrorException.NotFound notFoundException) {
            throw new RuntimeException("Sesuaikan restTemplate url nya lagi!");
        } catch (RuntimeException e) {
            throw new RuntimeException("Terjadi kesalahan saat membuat transaksi: " + e.getMessage());
        } catch (Exception e) {
            logger.info(e.getMessage());
            throw new RuntimeException("Terjadi kesalahan saat membuat transaksi.");
        }
    }

    @Override
    public void editPengembalian(Long idTransaksi, String newNotePengembalian, Integer newTambahanHari) {
        // Temukan entitas PengembalianEntity berdasarkan idTransaksi
        Optional<PengembalianEntity> pengembalianOptional = pengembalianRepository.findByTransaksiIdTransaksi(idTransaksi);

        if (pengembalianOptional.isPresent()) {
            PengembalianEntity pengembalianEntity = pengembalianOptional.get();

            // Ubah notePengembalian dan tambahanHari pada pengembalianEntity
            pengembalianEntity.setNotePengembalian(newNotePengembalian);
            pengembalianEntity.setTambahanHari(newTambahanHari);

            // Hitung totalBayar berdasarkan hargaSewa dan tambahanHari
            TransaksiEntity transaksiEntity = pengembalianEntity.getTransaksi();
            Integer hargaSewa = transaksiEntity.getHargaSewa();

            Date tanggalSewa = transaksiEntity.getTanggalSewa();
            Date tanggalKembali = transaksiEntity.getTanggalKembali();

            long millisecondsPerDay = 24 * 60 * 60 * 1000; // Jumlah milidetik dalam sehari
            long selisihHari = (tanggalKembali.getTime() - tanggalSewa.getTime()) / millisecondsPerDay;
            int totalHarga = hargaSewa * ((int) selisihHari + 1);

            // Hitung totalBayar dengan menambahkan hargaSewa per hari tambahanHari
            int totalBayar = totalHarga + (hargaSewa * newTambahanHari);

            // Set totalBayar pada pengembalianEntity
            pengembalianEntity.setTotalBayar(totalBayar);

            // Simpan perubahan pada pengembalianEntity
            pengembalianRepository.save(pengembalianEntity);
        } else {
            throw new EntityNotFoundException("idTransaksi " + idTransaksi + " tidak ditemukan.");
        }
    }

    @Override
    public void editStatusSewa(Long idTransaksi, String newStatusSewa) {
        Optional<TransaksiEntity> transaksiOptional = transaksiRepository.findById(idTransaksi);

        if (transaksiOptional.isPresent()) {
            TransaksiEntity transaksiEntity = transaksiOptional.get();

            // Mendapatkan data pengembalian (jika ada)
            Optional<PengembalianEntity> pengembalianOptional = pengembalianRepository.findByTransaksiIdTransaksi(idTransaksi);
            if (pengembalianOptional.isPresent()) {
                PengembalianEntity pengembalianEntity = pengembalianOptional.get();
                String currentStatusSewa = pengembalianEntity.getStatusSewa();

                if (!currentStatusSewa.equals(newStatusSewa)) {
                    // Ubah statusSewa pada pengembalianEntity
                    pengembalianEntity.setStatusSewa(newStatusSewa);

                    // Simpan perubahan pada pengembalianEntity
                    pengembalianRepository.save(pengembalianEntity);

                    // Jika statusSewa berubah menjadi "selesai", ubah statusKendaraan menjadi true
                    if ("selesai".equals(newStatusSewa)) {
                        Integer idKendaraan = transaksiEntity.getIdKendaraan();
                        String ubahStatusKendaraanUrl2 = String.format( yuridan + "/kendaraan/status/" + idKendaraan + "/true");
                        restTemplate.put(ubahStatusKendaraanUrl2, null);

                        Integer idPenyewa = transaksiEntity.getIdPenyewa();
                        String ubahStatusSedangSewaUrl1 = String.format( charlie + "/penyewa/sedang-sewa/" + idPenyewa + "/false");
                        restTemplate.put(ubahStatusSedangSewaUrl1, null);
                    }
                }
            }
        }
    }

    @Override
    public List<TransaksiDTO> findAllTransaksi() {
        List<TransaksiEntity> transaksiEntities = null;
        List<TransaksiDTO> result = null;
        try{
            transaksiEntities = transaksiRepository.findAll();
            result = mapToTransaksiDTOWithDetails(transaksiEntities);
        }catch (Exception e){
            logger.info(e.getMessage());
        }
        return result;
    }

    @Override
    public TransaksiDTO findTransaksiById(Long idTransaksi) {
        Optional<TransaksiEntity> transaksiOptional = transaksiRepository.findById(idTransaksi);
        if (transaksiOptional.isPresent()) {
            TransaksiEntity transaksiEntity = transaksiOptional.get();
            TransaksiDTO transaksiDTO = mapToTransaksiDTO(transaksiEntity);

            // Cek apakah ada PengembalianEntity untuk transaksi ini
            Optional<PengembalianEntity> pengembalianOptional = pengembalianRepository.findByTransaksiIdTransaksi(idTransaksi);
            if (pengembalianOptional.isPresent()) {
                PengembalianEntity pengembalianEntity = pengembalianOptional.get();
                PengembalianDTO pengembalianDTO = new PengembalianDTO();
                pengembalianDTO.setIdPengembalian(pengembalianEntity.getIdPengembalian());
                pengembalianDTO.setStatusSewa(pengembalianEntity.getStatusSewa());
                pengembalianDTO.setNotePengembalian(pengembalianEntity.getNotePengembalian());
                pengembalianDTO.setTambahanHari(pengembalianEntity.getTambahanHari());
                pengembalianDTO.setTotalBayar(pengembalianEntity.getTotalBayar());

                transaksiDTO.setPengembalian(pengembalianDTO);
            }

            return transaksiDTO;
        }
        return null;
    }

    private List<TransaksiDTO> mapToTransaksiDTOWithDetails(List<TransaksiEntity> transaksiEntities) {
        try {
            return transaksiEntities.stream()
                    .map(transaksiEntity -> {
                        TransaksiDTO transaksiDTO = mapToTransaksiDTO(transaksiEntity);
                        return transaksiDTO;
                    })
                    .collect(Collectors.toList());
        }catch (Exception e){
            logger.info(e.getMessage());
            return null;
        }

    }

    private TransaksiDTO mapToTransaksiDTO(TransaksiEntity transaksiEntity) {
        TransaksiDTO transaksiDTO = new TransaksiDTO();

        try{
            transaksiDTO.setIdTransaksi(transaksiEntity.getIdTransaksi());
            transaksiDTO.setVersiKendaraan(transaksiEntity.getVersiKendaraan());
            transaksiDTO.setTanggalSewa(transaksiEntity.getTanggalSewa());
            transaksiDTO.setTanggalKembali(transaksiEntity.getTanggalKembali());
            transaksiDTO.setHargaSewa(transaksiEntity.getHargaSewa());
            transaksiDTO.setTotalHargaSewa(transaksiEntity.getTotalHargaSewa());
            logger.info("step 1");
            // Mendapatkan data kendaraan
            Integer idKendaraan = transaksiEntity.getIdKendaraan();

            Integer versiKendaraan = transaksiEntity.getVersiKendaraan();
            KendaraanResponse kendaraanResponse = getKendaraanDetails(idKendaraan, versiKendaraan);
            transaksiDTO.setKendaraan(kendaraanResponse);
            transaksiDTO.setIdKendaraan(transaksiEntity.getIdKendaraan());

            // Mendapatkan data penyewa
            Integer idPenyewa = transaksiEntity.getIdPenyewa();
            PenyewaResponse penyewaResponse = getPenyewaDetails(idPenyewa);
            transaksiDTO.setPenyewa(penyewaResponse);
            transaksiDTO.setIdPenyewa(transaksiEntity.getIdPenyewa());

            logger.info("step 2");

            // Mendapatkan data pegawai
//        Integer idPegawai = transaksiEntity.getIdPegawai();
//        PegawaiResponse pegawaiResponse = getPegawaiDetails(idPegawai);
//        transaksiDTO.setPegawai(pegawaiResponse);
            transaksiDTO.setIdPegawai(transaksiEntity.getIdPegawai());

            Optional<PengembalianEntity> pengembalianOptional = pengembalianRepository.findByTransaksiIdTransaksi(transaksiEntity.getIdTransaksi());
            if (pengembalianOptional.isPresent()) {
                PengembalianEntity pengembalianEntity = pengembalianOptional.get();
                PengembalianDTO pengembalianDTO = new PengembalianDTO();
                pengembalianDTO.setIdPengembalian(pengembalianEntity.getIdPengembalian());
                pengembalianDTO.setStatusSewa(pengembalianEntity.getStatusSewa());
                pengembalianDTO.setNotePengembalian(pengembalianEntity.getNotePengembalian());
                pengembalianDTO.setTambahanHari(pengembalianEntity.getTambahanHari());
                pengembalianDTO.setTotalBayar(pengembalianEntity.getTotalBayar());

                transaksiDTO.setPengembalian(pengembalianDTO);
            }
            logger.info("finish");
        }catch (Exception e){
            logger.info(e.getMessage());
        }

        return transaksiDTO;
    }

    private KendaraanResponse getKendaraanDetails(Integer idKendaraan, Integer versiKendaraan) {
        String kendaraanUrl = String.format( yuridan + "/kendaraan/find-global/id:" + idKendaraan + "/v:" + versiKendaraan);
        KendaraanResponse kendaraanResponse = restTemplate.getForObject(kendaraanUrl, KendaraanResponse.class);
        return kendaraanResponse;
    }

    private PenyewaResponse getPenyewaDetails(Integer idPenyewa) {
        String penyewaUrl = String.format( charlie + "/penyewa/find/" + idPenyewa);
        PenyewaResponse penyewaResponse = restTemplate.getForObject(penyewaUrl, PenyewaResponse.class);
        return penyewaResponse;
    }

//    private PegawaiResponse getPegawaiDetails(Integer idPegawai) {
//        String pegawaiUrl = String.format("http://" + server + ":" + portPegawai + "/karyawan/" + idPegawai);
//        PegawaiResponse pegawaiResponse = restTemplate.getForObject(pegawaiUrl, PegawaiResponse.class);
//        return pegawaiResponse;
//    }
}
