package android.print;

import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;

import java.io.File;

/**
 * WebView'in yazdırma çıktısını, yazdırma ekranı göstermeden doğrudan PDF dosyasına yazar.
 * LayoutResultCallback / WriteResultCallback kurucuları paket içi olduğu için bu sınıf
 * android.print paketinde durur.
 */
public final class PdfPrint {

    public interface Callback {
        void onDone(boolean ok, String error);
    }

    private final PrintAttributes attributes;

    public PdfPrint(PrintAttributes attributes) {
        this.attributes = attributes;
    }

    public void print(final PrintDocumentAdapter adapter, final File out, final Callback callback) {
        adapter.onStart();
        adapter.onLayout(null, attributes, null, new PrintDocumentAdapter.LayoutResultCallback() {
            @Override
            public void onLayoutFinished(PrintDocumentInfo info, boolean changed) {
                final ParcelFileDescriptor pfd;
                try {
                    pfd = ParcelFileDescriptor.open(out,
                            ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE
                                    | ParcelFileDescriptor.MODE_READ_WRITE);
                } catch (Exception e) {
                    callback.onDone(false, e.getMessage());
                    return;
                }
                adapter.onWrite(new PageRange[]{PageRange.ALL_PAGES}, pfd, new CancellationSignal(),
                        new PrintDocumentAdapter.WriteResultCallback() {
                            @Override
                            public void onWriteFinished(PageRange[] pages) {
                                close(pfd);
                                adapter.onFinish();
                                callback.onDone(true, null);
                            }

                            @Override
                            public void onWriteFailed(CharSequence error) {
                                close(pfd);
                                adapter.onFinish();
                                callback.onDone(false, error == null ? "PDF yazılamadı" : error.toString());
                            }

                            @Override
                            public void onWriteCancelled() {
                                close(pfd);
                                adapter.onFinish();
                                callback.onDone(false, "İptal edildi");
                            }
                        });
            }

            @Override
            public void onLayoutFailed(CharSequence error) {
                adapter.onFinish();
                callback.onDone(false, error == null ? "Sayfa düzeni oluşturulamadı" : error.toString());
            }

            @Override
            public void onLayoutCancelled() {
                adapter.onFinish();
                callback.onDone(false, "İptal edildi");
            }
        }, null);
    }

    private static void close(ParcelFileDescriptor pfd) {
        try {
            pfd.close();
        } catch (Exception ignored) {
        }
    }
}
