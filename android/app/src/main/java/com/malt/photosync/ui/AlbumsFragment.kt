package com.malt.photosync.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.malt.photosync.data.ConfigStore
import com.malt.photosync.databinding.FragmentAlbumsBinding
import com.malt.photosync.databinding.ItemAlbumBinding
import com.malt.photosync.scan.MediaScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 相册目录多选（PRD F1） */
class AlbumsFragment : Fragment() {

    private var _b: FragmentAlbumsBinding? = null
    private val b get() = _b!!
    private lateinit var cfg: ConfigStore

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) loadBuckets()
        else requireActivity().supportFragmentManager.popBackStack()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = FragmentAlbumsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        cfg = ConfigStore(requireContext())
        b.recycler.layoutManager = LinearLayoutManager(requireContext())
        b.btnDone.setOnClickListener { requireActivity().supportFragmentManager.popBackStack() }

        val needed = mediaPermissions()
        if (needed.all {
                ContextCompat.checkSelfPermission(requireContext(), it) ==
                    PackageManager.PERMISSION_GRANTED
            }
        ) {
            loadBuckets()
        } else {
            permLauncher.launch(needed)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    private fun mediaPermissions(): Array<String> = if (android.os.Build.VERSION.SDK_INT >= 33) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun loadBuckets() {
        val selected = cfg.bucketIds.toMutableSet()
        viewLifecycleOwner.lifecycleScope.launch {
            val buckets = withContext(Dispatchers.IO) {
                MediaScanner(requireContext().contentResolver).buckets()
            }
            if (!isAdded) return@launch
            b.recycler.adapter = AlbumsAdapter(buckets, selected) { id, name, checked ->
                val cfgNow = ConfigStore(requireContext())
                val ids = cfgNow.bucketIds.toMutableSet()
                val names = cfgNow.bucketNames.toMutableMap()
                if (checked) {
                    ids += id
                    names[id] = name
                } else {
                    ids -= id
                    names -= id
                }
                cfgNow.bucketIds = ids
                cfgNow.bucketNames = names
            }
        }
    }
}

private class AlbumsAdapter(
    private val buckets: List<MediaScanner.Bucket>,
    private val selected: MutableSet<String>,
    private val onToggle: (String, String, Boolean) -> Unit
) : androidx.recyclerview.widget.RecyclerView.Adapter<AlbumsAdapter.VH>() {

    class VH(val binding: ItemAlbumBinding) : androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemAlbumBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = buckets.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val bucket = buckets[position]
        holder.binding.name.text = bucket.name
        val parts = mutableListOf<String>()
        if (bucket.photos > 0) parts.add("${bucket.photos} 张照片")
        if (bucket.videos > 0) parts.add("${bucket.videos} 个视频")
        holder.binding.count.text = parts.joinToString(" / ")
        holder.binding.check.setOnCheckedChangeListener(null)
        holder.binding.check.isChecked = selected.contains(bucket.id)
        holder.binding.check.setOnCheckedChangeListener { _, isChecked ->
            onToggle(bucket.id, bucket.name, isChecked)
        }
    }
}
