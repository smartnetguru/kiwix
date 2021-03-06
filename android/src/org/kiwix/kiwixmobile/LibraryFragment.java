package org.kiwix.kiwixmobile;

import android.app.IntentService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.StatFs;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import butterknife.BindView;
import butterknife.ButterKnife;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;

import org.kiwix.kiwixmobile.database.BookDao;
import org.kiwix.kiwixmobile.database.KiwixDatabase;
import org.kiwix.kiwixmobile.downloader.DownloadIntent;
import org.kiwix.kiwixmobile.downloader.DownloadService;
import org.kiwix.kiwixmobile.library.LibraryAdapter;
import org.kiwix.kiwixmobile.library.entity.LibraryNetworkEntity;
import org.kiwix.kiwixmobile.network.KiwixService;
import org.kiwix.kiwixmobile.utils.LanguageUtils;
import org.kiwix.kiwixmobile.utils.ShortcutUtils;

import rx.android.schedulers.AndroidSchedulers;

import static org.kiwix.kiwixmobile.utils.ShortcutUtils.stringsGetter;

public class LibraryFragment extends Fragment implements AdapterView.OnItemClickListener {

  @BindView(R.id.library_list) ListView libraryList;
  @BindView(R.id.progressbar_layout) RelativeLayout progressBar;
  @BindView(R.id.progressbar_message) TextView progressBarMessage;


  private KiwixService kiwixService;

  public LinearLayout llLayout;

  private LinkedList<LibraryNetworkEntity.Book> books;

  public static DownloadService mService = new DownloadService();

  private boolean mBound;

  private boolean active;

  public static LibraryAdapter libraryAdapter;

  private DownloadServiceConnection mConnection = new DownloadServiceConnection();

  private ConnectivityManager conMan;

  private BookDao bookDao;


    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        FragmentActivity faActivity  = (FragmentActivity)    super.getActivity();
        // Replace LinearLayout by the type of the root element of the layout you're trying to load
        llLayout    = (LinearLayout)    inflater.inflate(R.layout.activity_library, container, false);
        // Of course you will want to faActivity and llLayout in the class and not this method to access them in the rest of
        // the class, just initialize them here

        // Don't use this method, it's handled by inflater.inflate() above :
        // setContentView(R.layout.activity_layout);
      ButterKnife.bind(this, llLayout);
      kiwixService = ((KiwixApplication) super.getActivity().getApplication()).getKiwixService();
      conMan = (ConnectivityManager) super.getActivity().getSystemService(Context.CONNECTIVITY_SERVICE);
      NetworkInfo network = conMan.getActiveNetworkInfo();
      bookDao = new BookDao(new KiwixDatabase(super.getActivity()));
      if (network != null && network.isConnected()) {
        kiwixService.getLibrary()
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(library -> {
              books = library.getBooks();
              if (active) {
                LinkedList<LibraryNetworkEntity.Book> booksCopy = new LinkedList<LibraryNetworkEntity.Book>(books);
                LinkedList<LibraryNetworkEntity.Book> booksAdditions = new LinkedList<LibraryNetworkEntity.Book>();
                for (LibraryNetworkEntity.Book book : books) {

                  if (book.getLanguage() != null && book.getLanguage().equals(getActivity().getResources().getConfiguration().locale.getISO3Language())) {
                    booksCopy.remove(book);
                    booksAdditions.addFirst(book);
                  }
                }
                for (LibraryNetworkEntity.Book book : booksAdditions) {
                  booksCopy.addFirst(book);
                }
                books = booksCopy;
                libraryAdapter = new LibraryAdapter(super.getActivity(), books);
                libraryList.setAdapter(libraryAdapter);
                progressBar.setVisibility(View.GONE);
              }
            });


        libraryList.setOnItemClickListener(this);

        active = true;
      } else {
        progressBar.setVisibility(View.INVISIBLE);
        progressBarMessage.setText("No network connection");
      }
        // The FragmentActivity doesn't contain the layout directly so we must use our instance of     LinearLayout :
        //llLayout.findViewById(R.id.someGuiElement);
        // Instead of :
        // findViewById(R.id.someGuiElement);
        return llLayout; // We must return the loaded Layout
    }

  @Override
  public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    progressBar.setVisibility(View.VISIBLE);

  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();
    active = false;
    if (mBound) {
      super.getActivity().unbindService(mConnection.downloadServiceInterface);
      mBound = false;

    }

  }

  @Override
  public void onItemClick(AdapterView<?> parent, View view, int position, long id) {

    if (getSpaceAvailable() < Long.parseLong(books.get(position).getSize()) * 1024f){
      Toast.makeText(super.getActivity(), stringsGetter(R.string.download_no_space, super.getActivity())
          + "\n" +stringsGetter(R.string.space_available, super.getActivity()) + " "
          + bytesToHuman(getSpaceAvailable()), Toast.LENGTH_LONG).show();
      return;
    }
    bookDao.saveBook(books.get(position));
    if (Build.VERSION.SDK_INT >= 23) {
      NetworkInfo network = conMan.getActiveNetworkInfo();
      if (network.getType() != ConnectivityManager.TYPE_WIFI){
        mobileDownloadDialog(position);
      } else {
        downloadFile(position, books.get(position));
      }
    } else {
      NetworkInfo wifi = conMan.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
      if (!wifi.isConnected()){
        mobileDownloadDialog(position);
      } else {
        downloadFile(position, books.get(position));
      }
    }
  }

  public static String bytesToHuman (long size)
  {
    long KB = 1  * 1024;
    long MB = KB * 1024;
    long GB = MB * 1024;
    long TB = GB * 1024;
    long PB = TB * 1024;
    long EB = PB * 1024;

    if (size <  KB)                 return size + " Bytes";
    if (size >= KB && size < MB)    return round3SF((double)size / KB) + " KB";
    if (size >= MB && size < GB)    return round3SF((double)size / MB) + " MB";
    if (size >= GB && size < TB)    return round3SF((double)size / GB) + " GB";
    if (size >= TB && size < PB)    return round3SF((double)size / TB) + " TB";
    if (size >= PB && size < EB)    return round3SF((double)size / PB) + " PB";
    if (size >= EB)                 return round3SF((double)size / EB) + " EB";

    return "???";
  }

  public static String round3SF(double size){
    BigDecimal bd = new BigDecimal(size);
    bd = bd.round(new MathContext(3));
    return String.valueOf(bd.doubleValue());
  }


  public void mobileDownloadDialog(int position) {
    new AlertDialog.Builder(super.getActivity())
        .setMessage(ShortcutUtils.stringsGetter(R.string.download_over_network, super.getActivity()))
        .setPositiveButton(getResources().getString(android.R.string.yes), new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int which) {
            downloadFile(position, books.get(position));
          }
        })
        .setNegativeButton(android.R.string.no, new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int which) {
          }
        })
        .show();
  }

  public void downloadFile(int position, LibraryNetworkEntity.Book book) {
    bookDao.saveBook(book);
    Toast.makeText(super.getActivity(), stringsGetter(R.string.download_started_library, super.getActivity()), Toast.LENGTH_LONG).show();
    Intent service = new Intent(super.getActivity(), DownloadService.class);
    service.putExtra(DownloadIntent.DOWNLOAD_URL_PARAMETER, books.get(position).getUrl());
    service.putExtra(DownloadIntent.DOWNLOAD_ZIM_TITLE, books.get(position).getTitle());
    service.putExtra("Book", book);
    super.getActivity().startService(service);
    mConnection = new DownloadServiceConnection();
    super.getActivity().bindService(service, mConnection.downloadServiceInterface, Context.BIND_AUTO_CREATE);
    ZimManageActivity manange = (ZimManageActivity) super.getActivity();
    manange.displayDownloadInterface();
  }

  public static long getSpaceAvailable() {
    return Environment.getExternalStorageDirectory().getFreeSpace();
  }

  public class DownloadServiceConnection {
    public DownloadServiceInterface downloadServiceInterface;
    public boolean bound;

    public DownloadServiceConnection() {
      downloadServiceInterface = new DownloadServiceInterface();
    }

    public class DownloadServiceInterface implements ServiceConnection {

      @Override
      public void onServiceConnected(ComponentName className,
                                     IBinder service) {
        // We've bound to LocalService, cast the IBinder and get LocalService instance
        DownloadService.LocalBinder binder = (DownloadService.LocalBinder) service;
        mService = binder.getService();
        mBound = true;
      }

      @Override
      public void onServiceDisconnected(ComponentName arg0) {
        bound = false;
      }
    }

  }

}